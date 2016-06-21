package org.astrogrid.samp.tls;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.astrogrid.samp.DataException;
import org.astrogrid.samp.SampUtils;
import org.astrogrid.samp.client.ClientProfile;
import org.astrogrid.samp.client.SampException;
import org.astrogrid.samp.httpd.HttpServer;
import org.astrogrid.samp.hub.HubProfile;
import org.astrogrid.samp.hub.KeyGenerator;
import org.astrogrid.samp.hub.MessageRestriction;
import org.astrogrid.samp.web.ClientAuthorizer;
import org.astrogrid.samp.web.CorsHttpServer;
import org.astrogrid.samp.web.HubSwingClientAuthorizer;
import org.astrogrid.samp.web.ListMessageRestriction;
import org.astrogrid.samp.web.UrlTracker;
import org.astrogrid.samp.web.WebClientProfile;
import org.astrogrid.samp.web.WebHubXmlRpcHandler;
import org.astrogrid.samp.xmlrpc.SampXmlRpcClient;
import org.astrogrid.samp.xmlrpc.SampXmlRpcClientFactory;
import org.astrogrid.samp.xmlrpc.XmlRpcKit;

/**
 * HubProfile implementation that can be plugged into the JSAMP Hub
 * to support HTTPS-hosted web clients.
 *
 * <p>Highly experimental at time of writing.
 *
 * @author   Mark Taylor
 * @since    14 March 2016
 */
public class TlsHubProfile implements HubProfile {

    private final int port_;
    private final ClientAuthorizer auth_;
    private final MessageRestriction mrestrict_;
    private final SampXmlRpcClientFactory xClientFactory_;
    private final KeyGenerator keyGen_;
    private ExecutorService callExecutor_;
    private ExecutorService collectorExecutor_;
    private WebHubXmlRpcHandler wxHandler_;
    private HttpServer hServer_;
    private static final Logger logger_ =
        Logger.getLogger( TlsHubProfile.class.getName() );

    public static final int NUDGE_PORT = 21013;
    public static final String NUDGE_PATH = "/nudge";
    public static final String RELAYURL_PARAM = "relay";
    public static final String CALLTAG_PARAM = "callTag";
    public static final String COLLECTOR_PREFIX = "samp.tlshub.";
    public static final String DISPENSER_PREFIX = "samp.tlsfwd.";
    public static final String REFERER_KEY = "samp.referer";
    private static final int TIMEOUT_SEC = 10;

    /**
     * Constructor.
     *
     * @param  port  localhost port number for image nudge
     * @param   auth  client authorizer implementation
     * @param   mrestrict  restriction for permitted outward MTypes
     * @param   xClientFactory   factory for XmlRpcClients
     * @param   keyGen  key generator for private keys
     */
    public TlsHubProfile( int port, ClientAuthorizer auth,
                          MessageRestriction mrestrict,
                          SampXmlRpcClientFactory xClientFactory,
                          KeyGenerator keyGen ) {
        port_ = port;
        auth_ = auth;
        mrestrict_ = mrestrict;
        xClientFactory_ = xClientFactory;
        keyGen_ = keyGen;
    }

    /**
     * Constructs an instance with default properties.
     */
    public TlsHubProfile() {
        this( NUDGE_PORT,
              new HubSwingClientAuthorizer( null,
                                            TlsCredentialPresenter.INSTANCE ),
              ListMessageRestriction.DEFAULT,
              XmlRpcKit.getInstance().getClientFactory(),
              new KeyGenerator( "tls:", 24, KeyGenerator.createRandom() ) );
    }

    public String getProfileName() {
        return "TLS";
    }

    public synchronized void start( ClientProfile clientProfile )
            throws IOException {
        if ( isRunning() ) {
            logger_.info( getProfileName() + " profile already running" );
            return;
        }
        ServerSocket sock = new ServerSocket();
        sock.setReuseAddress( true );
        sock.bind( new InetSocketAddress( port_ ) );
        hServer_ = new HttpServer( sock );
        hServer_.addHandler( new NudgeHandler() );
        URL baseUrl = hServer_.getBaseUrl();  // not sure about that
        UrlTracker urlTracker = null;  // not sure about that
        wxHandler_ = new WebHubXmlRpcHandler( clientProfile, auth_, keyGen_,
                                              baseUrl, urlTracker );
        callExecutor_ = Executors.newCachedThreadPool( new ThreadFactory() {
            public Thread newThread( Runnable r ) {
                return new Thread( r, "TLS-SAMP_relayed_call_invoker" );
            }
        } );
        collectorExecutor_ = callExecutor_;
        hServer_.start();
    }

    public synchronized void stop() {
        if ( ! isRunning() ) {
            logger_.info( getProfileName() + " profile already stopped" );
            return;
        }
        hServer_.stop();
        callExecutor_.shutdown();
        collectorExecutor_.shutdown();
        hServer_ = null;
        wxHandler_ = null;
    }

    public synchronized boolean isRunning() {
        return hServer_ != null;
    }

    public MessageRestriction getMessageRestriction() {
        return mrestrict_;
    }

    /**
     * HTTP handler invoked by localhost web client.
     * It returns a small image, but retrieval has side-effects,
     * namely causing this profile to go looking for SAMP messages
     * queued on the hub relay.
     */
    private class NudgeHandler implements HttpServer.Handler {
        private int iseq_;
        public HttpServer.Response serveRequest( HttpServer.Request request ) {
            if ( ! isPermittedHost( request.getRemoteAddress() ) ) {
                return CorsHttpServer.createNonLocalErrorResponse( request );
            }
            String method = request.getMethod();
            NudgeParsedUrl pu = new NudgeParsedUrl( request.getUrl() );
            String path = pu.getPath();
            final URL relayUrl = pu.getRelayUrl();
            final String callTag = pu.getCallTag();
            if ( NUDGE_PATH.equals( path ) ) {
                if ( ! "GET".equals( method ) ) {
                    return HttpServer
                          .create405Response( new String[] { "GET" } );
                }
                else if ( pu.isInit() ) {
                    return ImageResponse.createToggleResponse( false );
                }
                else if ( relayUrl == null || callTag == null ) {
                    return HttpServer
                          .createErrorResponse( 400, "Bad tls-samp params" );
                }
                logger_.info( "Nudged to collect message from " + relayUrl 
                            + " with tag " + callTag );
                try {
                    collectorExecutor_.execute( new Runnable() {
                        public void run() {
                            collectCall( relayUrl, callTag );
                        }
                    } );
                }
                catch ( RejectedExecutionException e ) {
                    String msg = "Can't collect call " + callTag;
                    logger_.log( Level.WARNING, msg, e );
                    return HttpServer
                          .createErrorResponse( 500, msg, e );
                }
                HttpServer.Response response =
                    ImageResponse.createSpinResponse( iseq_++ );
                response.getHeaderMap().put( "Cache-Control", "no-cache" );
                return response;
            }
            else {
                return null;
            }
        }

        /**
         * Indicates whether a given remote address is permitted to access
         * this service.  Under normal circumstances this should be restricted
         * to the local host, otherwise anyone could tell the hub to go
         * looking for queued SAMP messages.
         *
         * @param  addr   client host address
         * @return  true iff the address is permitted to access this service
         */
        private boolean isPermittedHost( SocketAddress addr ) {
            return CorsHttpServer.isLocalHost( addr )
                || CorsHttpServer.isExtraHost( addr );
        }
    }

    /**
     * Invoked when a nudge has been received to retrieve calls from
     * a hub relay service.
     *
     * @param  relayUrl   URL of remote message relay service
     * @param  callTag    identifier of call to be collected
     */
    private void collectCall( URL relayUrl, String callTag ) {
        try {
            doCollectCall( relayUrl, callTag, TIMEOUT_SEC );
        }
        catch ( ConnectException e ) {
            logger_.log( Level.WARNING, "No hub relay at " + relayUrl );
        }
        catch ( Throwable e ) {
            logger_.log( Level.WARNING, "Call collection error", e );
        }
    }

    /**
     * Pulls queued SampCall objects from a remote hub relay and
     * submits them for processing.
     *
     * @param  relayUrl  URL of remote hub relay service
     * @param  callTag   tag of named call to collect
     * @param  timeoutSec  maximum wait time in seconds
     */
    private void doCollectCall( final URL relayUrl, String callTag,
                                int timeoutSec )
            throws IOException {
        final SampXmlRpcClient xClient =
            xClientFactory_.createClient( relayUrl );
        String timeoutStr = SampUtils.encodeInt( timeoutSec );
        List<?> pullParams = Arrays.asList( new String[] {
            callTag, timeoutStr,
        } );
        Object pulled =
            xClient.callAndWait( DISPENSER_PREFIX + "pullCall", pullParams );
        if ( pulled instanceof Map ) {
            SampCall call = SampCall.asCall( (Map) pulled );
            if ( call.isEmpty() ) {
                logger_.warning( "Failed to collect call " + callTag
                               + " (timeout?)" );
            }
            else {
                handleCall( xClient, callTag, call, relayUrl );
            }
        }
        else {
            logger_.warning( "Pulled call was not a SAMP map: " + pulled );
        }
    }

    /**
     * Handles a SampCall object and passes the response back to the
     * remote service.
     *
     * @param  xClient   XML-RPC client for communicating with relay
     * @param  callTag   tag by which the serialized call was requested
     * @param  tlsCall     call object to be processed
     * @param  relayUrl   URL at which the hub relay resides
     */
    private void handleCall( SampXmlRpcClient xClient, String callTag,
                             SampCall call, URL relayUrl ) {
        String callStr =
              call.getMethodName().replaceFirst( COLLECTOR_PREFIX, "" )
            + " " + callTag;
        logger_.info( "Handling call: " + callStr );
        SampResult result = getCallResult( callTag, call, relayUrl );
        logger_.info( "Got result "
                    + ( result.containsKey( "samp.value" ) ? "success"
                                                           : "error" )
                    + " for " + callStr );

        // Pass the result back asynchronously to the relay.
        List resultParams = Arrays.asList( new Object[] { callTag, result } );
        try {
            xClient.callAndWait( DISPENSER_PREFIX + "receiveResult",
                                 resultParams );
        }
        catch ( IOException e ) {
            logger_.log( Level.WARNING,
                         "Failed to pass result back for " + callStr, e );
        }
    }

    /**
     * Does the local processing that services the serialized call,
     * and return the corresponding serialized result.
     *
     * @param  callTag   tag by which the serialized call was requested
     * @param  tlsCall    call object to be processed, expected to
     *                    have a samp.tlshub.* methodName
     * @param  relayUrl   URL at which the hub relay resides
     * @return   serialized result object, for success or error
     */
    private SampResult getCallResult( String callTag, SampCall tlsCall,
                                      URL relayUrl ) {

        // We transform the call to the corresponding Web Profile API call,
        // use a WebProfile handler, and then transform the result back
        // from the Web Profile API.  The differences are pretty small.
        SampCall webCall;
        try {
            webCall = tlsToWebCall( callTag, tlsCall, relayUrl );
        }
        catch ( Throwable e ) {
            return SampResult
                  .createErrorResult( "Bad call " + tlsCall.getMethodName()
                                    + ": " + e );
        }
        String webMethodName = webCall.getMethodName();
        List webParams = webCall.getParams();
        HttpServer.Request fakeRequest =
            new HttpServer.Request( null, null, new HashMap(), null, null );
        if ( wxHandler_.canHandleCall( webMethodName ) ) {
            try {
                Object webOutput =
                    wxHandler_.handleCall( webMethodName, webParams,
                                           fakeRequest );
                Object tlsOutput = webToTlsOutput( webMethodName, webOutput );
                return SampResult.createSuccessResult( tlsOutput );
            }
            catch ( Throwable e ) {
                return SampResult.createErrorResult( e.toString() );
            }
        }
        else {
            return SampResult.createErrorResult( "Unknown method "
                                               + tlsCall.getMethodName() );
        }
    }

    /**
     * Converts a serialized call object from a samp.webhub.* (Web Profile)
     * call to the corresponding samp.tlshub.* (TLS Profile) call.
     * In most cases, the changes are quite minimal; this method does
     * not (need to) know much about the details of either API.
     *
     * @param  callTag   tag by which the serialized call was requested
     * @param  tlsCall    call object to be processed, expected to
     *                    have a samp.tlshub.* methodName
     * @param  relayUrl   URL at which the hub relay resides
     * @return   serialized call with a samp.webhub.* methodName
     */
    private static SampCall tlsToWebCall( String callTag, SampCall tlsCall,
                                          URL relayUrl ) throws SampException {

        // Modify prefix.
        String tlsMethodName = tlsCall.getMethodName();
        if ( tlsMethodName == null ||
             ! tlsMethodName.startsWith( COLLECTOR_PREFIX ) ) {
            throw new SampException( "Ignoring unexpected collected call "
                                   + tlsMethodName );
        }
        String baseMethodName =
            tlsMethodName.substring( COLLECTOR_PREFIX.length() );
        String webMethodName =
            WebClientProfile.WEBSAMP_HUB_PREFIX + baseMethodName;

        // Remove first parameter, and check if it matches callTag.
        List tlsParams = tlsCall.getParams();
        if ( tlsParams.size() == 0 ) {
            throw new SampException( "No call tag" );
        }
        Object tagParam = tlsParams.get( 0 );
        if ( ! callTag.equals( tagParam ) ) {
            logger_.warning( "Call tag mismatch for " + baseMethodName
                           + ": " + tagParam + " != " + callTag );
        }
        List webParams =
            new ArrayList( tlsParams.subList( 1, tlsParams.size() ) );

        // Treat register call specially.
        if ( "register".equals( baseMethodName ) ) {
            if ( webParams.size() > 0 && webParams.get( 0 ) instanceof Map ) {
                Map securityMap = new LinkedHashMap( (Map) webParams.get( 0 ) );
                if ( relayUrl != null ) {
                    securityMap.put( TlsCredentialPresenter.RELAY_KEY,
                                     relayUrl.toString() );
                }
                webParams.set( 0, securityMap );
            }
        }

        // Return the result.
        return new SampCall( webMethodName, webParams );
    }

    /**
     * Converts the output of a Web Profile hub API method to the output
     * of the corresponding TLS Profile hub API method.
     * In most cases, the changes are very slight.
     *
     * @param   baseMethodName  method name without namespace previx
     * @param   webOutput   value that was returned from Web Profile
     *                      implementation of hub API method
     * @return  value that should be returned from TLS Profile
     *          implementation of hub API method
     */
    private static Object webToTlsOutput( String baseMethodName,
                                          Object webOutput ) {
        if ( ( WebClientProfile.WEBSAMP_HUB_PREFIX + "register")
            .equals( baseMethodName ) &&
             webOutput instanceof Map ) {
            Map map = new LinkedHashMap( (Map) webOutput );
            map.remove( WebClientProfile.URLTRANS_KEY );
            return map;
        }
        else {
            return webOutput;
        }
    }

    /**
     * Makes sense of a nudge URL.
     */
    private static class NudgeParsedUrl extends ParsedUrl {

        /**
         * Constructor.
         *
         * @param   localPart   local part of URL
         */
        NudgeParsedUrl( String localPart ) {
            super( localPart );
        }

        /**
         * Returns the URL of the remote hub relay service, as encoded in
         * this object.
         *
         * @return   remote URL for message retrieval, or null
         */
        URL getRelayUrl() {
            String relayUrl = getParams().get( RELAYURL_PARAM );
            if ( relayUrl == null ) {
                return null;
            }
            try {
                return new URL( relayUrl );
            }
            catch ( MalformedURLException e ) {
                logger_.warning( "Relay location not URL (" + relayUrl + ")" );
                return null;
            }
        }

        /**
         * Returns the identifier of the serialized call that should be
         * collected from the relay.
         *
         * @return  call tag
         */
        String getCallTag() {
            return getParams().get( CALLTAG_PARAM );
        }

        /**
         * Returns true if this is to be interpreted as an initialisation
         * (no request implicit).
         *
         * @return  true if this is an initialisation call
         */
        public boolean isInit() {
            return ! getParams().containsKey( RELAYURL_PARAM );
        }
    }
}
