package org.astrogrid.samp.tls;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.astrogrid.samp.DataException;
import org.astrogrid.samp.SampUtils;
import org.astrogrid.samp.client.SampException;
import org.astrogrid.samp.hub.KeyGenerator;
import org.astrogrid.samp.web.WebClientProfile;
import org.astrogrid.samp.xmlrpc.ActorHandler;
import org.astrogrid.samp.xmlrpc.SampXmlRpcHandler;

public abstract class XmlRpcBouncer {

    private final int collectMaxWaitSec_;
    private final int resultMaxWaitSec_;
    private final ConcurrentMap<String,BlockingQueue<SampCall>> submittedCalls_;
    private final SampXmlRpcHandler receiveHandler_;
    private final SampXmlRpcHandler dispenseHandler_;
    private static final String RESULT_KEY = "jsamp.bouncer.result";
    private static final String COLLECTED_KEY = "jsamp.bouncer.collected";
    private static final Logger logger_ =
        Logger.getLogger( XmlRpcBouncer.class.getName() );

    public XmlRpcBouncer( Random rnd ) {
        collectMaxWaitSec_ = 10;
        resultMaxWaitSec_ = 600;
        submittedCalls_ =
            new ConcurrentHashMap<String,BlockingQueue<SampCall>>();
        KeyGenerator keyGen = new KeyGenerator( "samp.tlsfwd", 16, rnd );

        // This one is what the SAMP client talks to.
        // It looks exactly like a normal hub interface.
        receiveHandler_ = new ReceiveHandler( keyGen );

        // This one is what the localhost hub tls profile talks to.
        // It has the methods defined in DispenseActor.
        dispenseHandler_ = new DispenseHandler();
    }

    public SampXmlRpcHandler getReceiveHandler() {
        return receiveHandler_;
    }

    public SampXmlRpcHandler getDispenseHandler() {
        return dispenseHandler_;
    }

    private BlockingQueue<SampCall> getQueue( String hostname ) {
        submittedCalls_.putIfAbsent( hostname,
                                     new LinkedBlockingQueue<SampCall>() );
        return submittedCalls_.get( hostname );
    }

    public abstract String getHostName( Object reqObj );

    private class ReceiveHandler implements SampXmlRpcHandler {
        private final KeyGenerator keyGen_;

        ReceiveHandler( KeyGenerator keyGen ) {
            keyGen_ = keyGen;
        }

        public boolean canHandleCall( String methodName ) {
            return methodName.startsWith( WebClientProfile.WEBSAMP_HUB_PREFIX );
        }

        public Object handleCall( final String methodName, List allParams,
                                  Object reqInfo )
                throws InterruptedException, SampException {
            String hostname = getHostName( reqInfo );
            if ( hostname == null ) {
                throw new SampException( "Can't determine hostname" );
            }
            List params = new ArrayList( allParams );
            String callTag = keyGen_.next();
            SampCall call = new SampCall( methodName, params, callTag );
            BlockingQueue<SampCall> queue = getQueue( hostname );
            if ( queue.offer( call ) ) {
                logger_.info( "Submitted call " + methodName );

                // Wait for call to be collected by localhost hub;
                // fail if timeout. 
                if ( waitForEntry( call, COLLECTED_KEY,
                                   collectMaxWaitSec_ * 1000 ) == null ) {
                    queue.remove( call );
                    throw new SampException( "No hub (bouncer timeout "
                                           + collectMaxWaitSec_ + "sec)" );
                }

                // Wait for result from localhost hub; fail if timeout.
                Object resultObj =
                    waitForEntry( call, RESULT_KEY, resultMaxWaitSec_ * 1000 );
                if ( resultObj == null ) {
                    throw new SampException( "No hub response"
                                           + " (bouncer timeout "
                                           + resultMaxWaitSec_ + "sec)" );
                }

                // Return result value or error.
                SampResult result = SampResult.asResult( (Map) resultObj );
                Object value = result.getValue();
                if ( value != null ) {
                    return value;
                }
                else {
                    throw new SampException( result.getError() );
                }
            }
            else {
                throw new SampException( "Too many calls queued"
                                       + " (" + queue.size() + ")" );
            }
        }
    }

    /**
     * The map must be managed such that map.notifyAll() is called when
     * the relevant entry may have been updated.
     */
    private static Object waitForEntry( Map map, String key,
                                        long timeoutMillis )
            throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMillis;
        synchronized( map ) {
            while ( true ) {
                Object value = map.get( key );
                if ( value != null ) {
                    return value;
                }
                long remainingTime = end - System.currentTimeMillis();
                if ( remainingTime <= 0 ) {
                    return null;
                }
                map.wait( remainingTime );
            }
        }
    }

    // API:
    //    void ping()
    //    List<SampCall> pullCalls(String timeout)
    //    void acceptResult(String callTag, SampResult result)
    //
    private class DispenseHandler implements SampXmlRpcHandler {
        private final Map<String,SampCall> dispensedCalls_;
        private static final String PREFIX = TlsHubProfile.DISPENSER_PREFIX;

        DispenseHandler() {
            dispensedCalls_ = new ConcurrentHashMap<String,SampCall>();
        }

        public boolean canHandleCall( String fqName ) {
            return fqName.startsWith( PREFIX );
        }

        public Object handleCall( String fqName, List params, Object reqInfo )
                throws Exception {
            String methodName;
            if ( fqName.startsWith( PREFIX ) ) {
                methodName = fqName.substring( PREFIX.length() );
            }
            else {
                throw new IllegalArgumentException( "No I can't" );
            }
            final Object retval;
            if ( "ping".equals( methodName ) ) {
                retval = null;
            }
            else if ( "pullCalls".equals( methodName ) ) {
                String hostname = getHostName( reqInfo );
                if ( hostname == null ) {
                    throw new SampException( "Can't determine hostname" );
                }
                if ( params.size() != 1 ||
                     ! ( params.get( 0 ) instanceof String ) ) {
                    throw new SampException( "Wrong params for " + fqName
                                           + "(String timeout)" );
                }
                String timeout = (String) params.get( 0 );
                retval = pullCalls( hostname, timeout );
            }
            else if ( "acceptResult".equals( methodName ) ) {
                if ( params.size() != 2 ||
                     ! ( params.get( 0 ) instanceof String ) ||
                     ! ( params.get( 1 ) instanceof Map ) ) {
                    throw new SampException( "Wrong params for " + fqName
                                           + "(String callTag, Map result)" );
                }
                String callTag = (String) params.get( 0 );
                Map result = (Map) params.get( 1 );
                acceptResult( callTag, result );
                retval = null;
            }
            else {
                throw new SampException( "Uknown dispenser method: " + fqName );
            }
            return retval == null ? "" : retval;
        }

        public List<SampCall> pullCalls( String hostname, String timeoutSec )
                throws InterruptedException {
            int nSec = SampUtils.decodeInt( timeoutSec );
            List<SampCall> callList = new ArrayList<SampCall>();
            BlockingQueue<SampCall> queue = getQueue( hostname );
            SampCall call = queue.poll( nSec, TimeUnit.SECONDS );
            if ( call != null ) {
                synchronized ( call ) {
                    call.put( COLLECTED_KEY, "1" );
                    call.notifyAll();
                }
                callList.add( call );
                for ( SampCall c = null; ( c = queue.poll() ) != null; ) {
                    callList.add( c );
                }
            }
            for ( SampCall c : callList ) {
                dispensedCalls_.put( c.getCallTag(), call );
                logger_.info( "Dispensed call: " + c.getOperationName() );
            }
            return callList;
        }

        public void acceptResult( String callTag, Map result )
                throws SampException, InterruptedException {
            SampCall call;
            call = dispensedCalls_.remove( callTag );
            if ( call != null ) {
                synchronized ( call ) {
                    call.put( RESULT_KEY, result );
                    call.notifyAll();
                }
            }
            else {
                throw new SampException( "acceptResult failed for phantom tag "
                                       + callTag );
            }
        }
    }
}
