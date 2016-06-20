package org.astrogrid.samp.tls;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.astrogrid.samp.SampUtils;
import org.astrogrid.samp.client.SampException;
import org.astrogrid.samp.xmlrpc.SampXmlRpcHandler;

/**
 * This class forwards XML-RPC calls from one third-party
 * client to another.
 * It receives serialized XML-RPC calls from a "submitter" client
 * (the web application), stores them internally, and dispenses
 * them on request to a different "servicer" client (the desktop hub).
 * Having dispensed them, it waits for (asynchronous) returned values,
 * and passes those back (synchronously, as the result of the original
 * XML-RPC call) to the submitter.
 *
 * <p>Both submitter and servicer should be running on the same host as each
 * other (though normally not the same as the one on which this relay
 * is running).
 * How the submitter tells the servicer to come looking for the
 * messages stored here is somebody else's problem (nudge).
 *
 * <p>To use this class, there must be some kind of harness that plugs it into
 * an HTTP server, allowing both submitter and servicer clients to call
 * into it.
 *
 * @author   Mark Taylor
 * @since    14 Mar 2016
 */
public abstract class XmlRpcRelay {

    private final boolean checkHostnames_;
    private final int collectMaxWaitSec_;
    private final int resultMaxWaitSec_;
    private final BlockingStore<String,SampCall> callStore_;
    private final SampXmlRpcHandler receiveHandler_;
    private final DispenseHandler dispenseHandler_;
    private static final String RESULT_KEY = "jsamp.relay.result";
    private static final String HOSTNAME_KEY = "jsamp.relay.hostname";
    private static final Logger logger_ =
        Logger.getLogger( XmlRpcRelay.class.getName() );

    /** See RFC2616, sec 14.36. */
    private static final String REFERER_HDR = "Referer";

    /**
     * Constructor.
     *
     * @param  checkHostnames  if true, ensure that the submitter and servicer
     *                         are on the same host for each named call
     */
    public XmlRpcRelay( boolean checkHostnames ) {
        checkHostnames_ = checkHostnames;
        collectMaxWaitSec_ = 10;
        resultMaxWaitSec_ = 600;
        callStore_ = new BlockingStore<String,SampCall>();

        // This one is what the submitter (SAMP client) talks to.
        // It looks quite like a normal hub interface, but every method
        // requires a new (unique, unguessable) string argument callTag
        // prepended to the argument list.
        receiveHandler_ = new ReceiveHandler();

        // This one is what the servicer (hub tls profile) talks to.
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
     * Returns a host identifier string given a request object.
     *
     * <p>The request object is the final parameter passed to the
     * {@link org.astrogrid.samp.xmlrpc.SampXmlRpcHandler#handleCall handleCall
     * method; its nature depends on the XmlRpc implementation within
     * which this relay is being harnessed.
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
     * which this relay is being harnessed.
     *
     * @param  reqInfo  information about an HTTP request
     * @param  headerName   name of HTTP header, case insensitive
     * @return  value for given header in request, or null if not present
     */
    public abstract String getHeader( Object reqInfo, String headerName );

    /**
     * Handler implementation for the receiver endpoint.
     */
    private class ReceiveHandler implements SampXmlRpcHandler {

        /**
         * Constructor.
         */
        ReceiveHandler() {
        }

        public boolean canHandleCall( String methodName ) {
            return methodName.startsWith( TlsHubProfile.COLLECTOR_PREFIX );
        }

        public Object handleCall( final String methodName, List params,
                                  Object reqInfo )
                throws InterruptedException, SampException {

            // Extract first parameter in list as call tag.
            if ( params.size() == 0 ||
                 ! ( params.get( 0 ) instanceof String ) ) {
                throw new SampException( "No call tag for TLS SAMP call "
                                       + methodName );
            }
            String callTag = (String) params.get( 0 );

            // Get convenient reference for user messages.
            String callStr = methodName
                            .replaceFirst( TlsHubProfile.COLLECTOR_PREFIX, "" )
                             + " " + callTag;

            // Construct a SampCall object corresponding to this submission.
            SampCall call = new SampCall( methodName, params );

            // Treat register call specially. */
            if ( ( TlsHubProfile.COLLECTOR_PREFIX + "register" )
                .equals( methodName ) &&
                 params.size() > 1 &&
                 params.get( 1 ) instanceof Map ) {
                Map securityMap = (Map) params.get( 1 );
                String referer = getHeader( reqInfo, REFERER_HDR );
                if ( referer != null ) {
                    securityMap.put( TlsHubProfile.REFERER_KEY, referer );
                }
            }

            // Store hostname if required.
            if ( checkHostnames_ ) {
                String hostname = getHostName( reqInfo );
                if ( hostname != null ) {
                    call.put( HOSTNAME_KEY, hostname );
                }
                else {
                    throw new SampException( "Can't determine hostname" );
                }
            }

            // Store the call for later retrieval, indexed by its tag.
            boolean isUnique = ! dispenseHandler_.hasTag( callTag )
                            && callStore_.putNew( callTag, call );
            if ( ! isUnique ) {
                throw new SampException( "Can't accept call with tag already "
                                       + "in use: " + callStr );
            }
            logger_.info( "Received call: " + callStr );

            // Wait for call to be collected by servicer; fail if timeout. 
            if ( callStore_.removeUntaken( callTag,
                                           collectMaxWaitSec_ * 1000 ) ) {
                throw new SampException( "No hub (relay timeout "
                                       + collectMaxWaitSec_ + "sec) for "
                                       + callStr );
            }
            logger_.info( "Dispensed call: " + callStr );

            // Call has been dispensed.
            // Wait for result from servicer; fail if timeout.
            Object resultObj =
                waitForEntry( call, RESULT_KEY, resultMaxWaitSec_ * 1000 );
            if ( ! ( resultObj instanceof Map ) ) {
                throw new SampException( "No hub response for " + callStr
                                       + " (relay timeout "
                                       + resultMaxWaitSec_ + "sec)" );
            }
            logger_.info( "Got result from call: " + callStr );

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
     *    SampCall pullCall(String callTag, String timeoutSec)
     *    void receiveResult(String callTag, SampResult result)
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

            // Handle pullCall method
            else if ( "pullCall".equals( methodName ) ) {
                if ( params.size() != 2 ||
                     ! ( params.get( 0 ) instanceof String ) ||
                     ! ( params.get( 1 ) instanceof String ) ) {
                    throw new SampException( "Wrong params for " + fqName
                                           + "(string callTag,"
                                           + " string timeoutSec)" );
                }
                String callTag = (String) params.get( 0 );
                int timeoutMillis =
                    SampUtils.decodeInt( (String) params.get( 1 ) ) * 1000;

                String reqHostname = checkHostnames_ ? getHostName( reqInfo )
                                                     : null;
                if ( checkHostnames_ && reqHostname == null ) {
                    throw new SampException( "Can't determine hostname" );
                }

                // Wait for the requested call to arrive.
                // In case of timeout it will be null.
                SampCall call = callStore_.take( callTag, timeoutMillis );
                retval = call;

                // Check it if necessary.
                if ( checkHostnames_ && call != null ) {
                    String callHostname = (String) call.get( HOSTNAME_KEY );
                    if ( ! reqHostname.equals( callHostname ) ) {
                        throw new SampException( "Hostname mismatch: "
                                               + reqHostname + " != "
                                               + callHostname );
                    }
                }

                // Prepare to receive a response corresponding to the call.
                if ( call != null ) {
                    dispensedCalls_.put( callTag, call );
                }
            }

            // Handle receiveResult method.
            else if ( "receiveResult".equals( methodName ) ) {
                if ( params.size() != 2 ||
                     ! ( params.get( 0 ) instanceof String ) ||
                     ! ( params.get( 1 ) instanceof Map ) ) {
                    throw new SampException( "Wrong params for " + fqName
                                           + "(string callTag, map result)" );
                }
                if ( checkHostnames_ ) {
                    String hostname = getHostName( reqInfo );
                    if ( hostname == null ) {
                        throw new SampException( "Can't determine hostname" );
                    }
                }
                String callTag = (String) params.get( 0 );
                Map result = (Map) params.get( 1 );
                receiveResult( callTag, result );
                retval = null;
            }

            // Unknown method.
            else {
                throw new SampException( "Uknown dispenser method: " + fqName );
            }

            // Make sure that a SAMP-friendly return value is returned.
            return retval == null ? "" : retval;
        }

        /**
         * Accepts the return value for a previously dispensed call,
         * as generated by the servicer.
         *
         * @param   callTag  unique token by which the call identified itself
         * @param   result   SAMP-friendly representation of XML-RPC call
         *                   return value
         */
        public void receiveResult( String callTag, Map result )
                throws SampException, InterruptedException {
            SampCall call = dispensedCalls_.remove( callTag );
            if ( call != null ) {
                synchronized ( call ) {
                    call.put( RESULT_KEY, result );
                    call.notifyAll();
                }
            }
            else {
                throw new SampException( "receiveResult failed for phantom tag "
                                       + callTag );
            }
        }

        /**
         * Indicates whether the given tag is currently in use.
         *
         * @param  callTag  tag string
         * @return   true iff <code>callTag</code> has a chance of clashing
         *           with an existing tag
         */
        boolean hasTag( String callTag ) {
            return dispensedCalls_.containsKey( callTag );
        }
    }
}
