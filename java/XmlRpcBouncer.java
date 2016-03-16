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

/**
 * This class forwards XML-RPC messages from one third-party
 * client to another.
 * It receives messages from a "submitter" client, stores them internally,
 * and dispensees them to a different "servicer" client on request.
 * It also holds returns responses from the servicer back to the
 * submitter.
 * Both submitter and servicer must be running on the same host as each
 * other (though normally not the same as the one on which this bouncer
 * is running).
 * How the submitter tells the servicer to come looking for the
 * messages stored here is somebody else's problem.
 *
 * To use this class, there must be some kind of harness that plugs it into
 * an HTTP server, allowing both  and dispenser clients to call
 * into it.  That harness must implement the abstract {@link #getHostName}
 * method in a suitable way.
 *
 * @author   Mark Taylor
 * @since    14 Mar 2016
 */
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

    /**
     * Constructor.
     *
     * @param   rnd  random number generator, used for token generation
     */
    public XmlRpcBouncer( Random rnd ) {
        collectMaxWaitSec_ = 10;
        resultMaxWaitSec_ = 600;
        submittedCalls_ =
            new ConcurrentHashMap<String,BlockingQueue<SampCall>>();
        KeyGenerator keyGen = new KeyGenerator( "samp.tlsfwd", 16, rnd );

        // This one is what the submitter (SAMP client) talks to.
        // It looks exactly like a normal hub interface.
        receiveHandler_ = new ReceiveHandler( keyGen );

        // This one is what the servicer (localhost hub tls profile) talks to.
        // It has the methods defined in DispenseActor.
        dispenseHandler_ = new DispenseHandler();
    }

    /**
     * Returns the XML-RPC handler that receives messages from the submitter.
     *
     * @return  XML-RPC handler
     */
    public SampXmlRpcHandler getReceiveHandler() {
        return receiveHandler_;
    }

    /**
     * Returns the XML-RPC handler that dispenses messages to the servicer.
     *
     * @return  dispenseHandler
     */
    public SampXmlRpcHandler getDispenseHandler() {
        return dispenseHandler_;
    }

    /**
     * Returns a call queue for a given host.
     *
     * @param   hostname  host identifier string
     * @return  queue
     */
    private BlockingQueue<SampCall> getQueue( String hostname ) {
        submittedCalls_.putIfAbsent( hostname,
                                     new LinkedBlockingQueue<SampCall>() );
        return submittedCalls_.get( hostname );
    }

    /**
     * Returns a host identifier string given a request object.
     *
     * <p>The request object is the final parameter passed to the
     * {@link org.astrogrid.samp.xmlrpc.SampXmlRpcHandler#handleCall handleCall}
     * method; its nature depends on the XmlRpc implementation within
     * which this bouncer is being harnessed.
     *
     * @param  reqInfo   information about an HTTP request
     * @return  hostname of request originator
     */
    public abstract String getHostName( Object reqInfo );

    /**
     * Returns the value of an HTTP header, if known, given a request object.
     *
     * <p>The request object is the final parameter passed to the
     * {@link org.astrogrid.samp.xmlrpc.SampXmlRpcHandler#handleCall handleCall}
     * method; its nature depends on the XmlRpc implementation within
     * which this bouncer is being harnessed.
     *
     * @param  reqInfo  information about an HTTP request
     * @param  headerName   name of HTTP header, case insensitive
     * @return  value for given header in request, or null if not present
     */
    public abstract String getHeader( Object reqInfo, String headerName );

    /**
     * Handler implementation for the receiver endpoint.
     *
     * <p>Currently, the XML-RPC interface for this is identical to that
     * used by the SAMP hub in the Web Profile.
     */
    private class ReceiveHandler implements SampXmlRpcHandler {
        private final KeyGenerator keyGen_;

        /**
         * Constructor.
         *
         * @param   keyGen  key generator
         */
        ReceiveHandler( KeyGenerator keyGen ) {
            keyGen_ = keyGen;
        }

        public boolean canHandleCall( String methodName ) {
            return methodName.startsWith( WebClientProfile.WEBSAMP_HUB_PREFIX );
        }

        public Object handleCall( final String methodName, List allParams,
                                  Object reqInfo )
                throws InterruptedException, SampException {

            // Construct a SampCall object corresponding to this submission.
            String hostname = getHostName( reqInfo );
            if ( hostname == null ) {
                throw new SampException( "Can't determine hostname" );
            }
            List params = new ArrayList( allParams );
            String callTag = keyGen_.next();
            SampCall call = new SampCall( methodName, params, callTag );
            String referer = getHeader( reqInfo,
                                        TlsAuthHeaderControl.REFERER_HDR );
            if ( referer != null ) {
                call.put( SampCall.REFERER_KEY, referer );
            }

            // Enqueue it on a queue specific to the host from which it is
            // being submitted.  It must be retrieved from the same host.
            BlockingQueue<SampCall> queue = getQueue( hostname );
            if ( queue.offer( call ) ) {
                logger_.info( "Submitted call " + methodName );
            }
            else {
                throw new SampException( "Too many calls queued"
                                       + " (" + queue.size() + ")" );
            }

            // Wait for call to be collected by servicer; fail if timeout. 
            if ( waitForEntry( call, COLLECTED_KEY,
                               collectMaxWaitSec_ * 1000 ) == null ) {
                queue.remove( call );
                throw new SampException( "No hub (bouncer timeout "
                                       + collectMaxWaitSec_ + "sec)" );
            }

            // Wait for result from servicer; fail if timeout.
            Object resultObj =
                waitForEntry( call, RESULT_KEY, resultMaxWaitSec_ * 1000 );
            if ( resultObj == null ) {
                throw new SampException( "No hub response for "
                                       + call.getOperationName()
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
    }

    /**
     * Wait until an entry with a given key has appeared in a map.
     * The map must be managed such that map.notifyAll() is called when
     * the relevant entry may have been updated.
     *
     * @param  map   map
     * @param  key   key to look out for
     * @param  timeoutMillis  maximum wait time in milliseconds
     * @return   value corresponding to <code>key</code>,
     *           or null in case of timeout
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

    /**
     * Handler implementation for the dispenser endpoint.
     *
     * This has three methods:
     * <pre>
     *    void ping()
     *    List<SampCall> pullCalls(String timeoutSec)
     *    void acceptResult(String callTag, SampResult result)
     * </pre>
     * These method names are prefixed with the string
     * {@link TlsHubProfile#DISPENSER_PREFIX}.
     */
    private class DispenseHandler implements SampXmlRpcHandler {
        private final Map<String,SampCall> dispensedCalls_;
        private static final String PREFIX = TlsHubProfile.DISPENSER_PREFIX;

        /**
         * Constructor.
         */
        DispenseHandler() {
            dispensedCalls_ = new ConcurrentHashMap<String,SampCall>();
        }

        public boolean canHandleCall( String fqName ) {
            return fqName.startsWith( PREFIX );
        }

        public Object handleCall( String fqName, List params, Object reqInfo )
                throws Exception {

            // Get unprefixed method name.
            String methodName;
            if ( fqName.startsWith( PREFIX ) ) {
                methodName = fqName.substring( PREFIX.length() );
            }
            else {
                throw new IllegalArgumentException( "No I can't" );
            }
            final Object retval;

            // Handle ping method.
            // No-op, just complete call.
            if ( "ping".equals( methodName ) ) {
                retval = null;
            }

            // Handler pullCalls method
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

            // Handler acceptResult method.
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
                logger_.info( "Accepted result for " + callTag );
                retval = null;
            }
            else {
                throw new SampException( "Uknown dispenser method: " + fqName );
            }

            // Make sure that a SAMP-friendly return value is returned.
            return retval == null ? "" : retval;
        }

        /**
         * Acquires a list of any calls that have been submitted by the
         * submitter.  It blocks for up to a given number of seconds
         * until at least one is available.
         *
         * @param  hostname   hostname of submitter (queue key)
         * @param  timeoutSec  number of seconds to block for, as SAMP integer
         * @return   list of calls; not null, but may be empty if no
         *           messages show up by timeout expiry
         */
        public List<SampCall> pullCalls( String hostname, String timeoutSec )
                throws InterruptedException {
            int nSec = SampUtils.decodeInt( timeoutSec );

            // Block until at least one call is available for dispensing,
            // and then gather that and any others that are immediately
            // available.  All these are removed from the queue.
            BlockingQueue<SampCall> queue = getQueue( hostname );
            SampCall call0 = queue.poll( nSec, TimeUnit.SECONDS );
            List<SampCall> callList = new ArrayList<SampCall>();
            if ( call0 != null ) {
                callList.add( call0 );
                for ( SampCall c = null; ( c = queue.poll() ) != null; ) {
                    callList.add( c );
                }
            }

            // Do some bookkeeping for each dispensed call.
            for ( SampCall c : callList ) {

                // Note that the call has been dispensed.
                synchronized ( c ) {
                    c.put( COLLECTED_KEY, "1" );
                    c.notifyAll();
                }
                logger_.info( "Dispensed call: " + c.getOperationName() + " "
                            + c.getCallTag() );

                // Prepare to receive a response corresponding to it.
                dispensedCalls_.put( c.getCallTag(), c );
            }

            // Return calls to be passed to the servicer.
            return callList;
        }

        /**
         * Accepts the return value for a previously dispensed call,
         * as generated by the servicer.
         *
         * @param   callTag  unique token by which the call identified itself
         * @param   result   SAMP-friendly representation of XML-RPC call
         *                   return value
         */
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
