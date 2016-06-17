package org.astrogrid.samp.tls;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
                                            TlsAuthHeaderControl.INSTANCE ),
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
            ParsedUrl pu = new ParsedUrl( request.getUrl() );
            String path = pu.path_;
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
     * @param  call     call object to be processed
     * @param  relayUrl   URL at which the hub relay resides
     */
    private void handleCall( SampXmlRpcClient xClient, String callTag,
                             SampCall call, URL relayUrl ) {
        String tlsMethodName = call.getMethodName();
        if ( tlsMethodName == null ||
             ! tlsMethodName.startsWith( COLLECTOR_PREFIX ) ) {
            logger_.info( "Ignoring unexpected collected call "
                        + tlsMethodName );
        }
        String baseMethodName =
            tlsMethodName.substring( COLLECTOR_PREFIX.length() );
        String webMethodName =
            WebClientProfile.WEBSAMP_HUB_PREFIX + baseMethodName;
        String callStr = baseMethodName + " " + callTag;
        logger_.info( "Handling relayed call " + callStr );

        // Do the local processing that services the serialized call.
        // We transform the call to the corresponding Web Profile API call,
        // use a WebProfile handler, and then transform the result back
        // from the Web Profile API.  The differences are very small.
        SampResult result;
        if ( wxHandler_.canHandleCall( webMethodName ) ) {
            List params = new ArrayList( call.getParams() );
            Object tagParam = params.size() > 0 ? params.remove( 0 ) : null;
            if ( ! callTag.equals( tagParam ) ) {
                logger_.warning( "Call tag mismatch for " + baseMethodName
                               + ": " + tagParam + " != " + callTag );
            }
            HttpServer.Request fakeRequest =
                createFakeRequest( call, relayUrl );
            try {
                Object webOutput =
                    wxHandler_.handleCall( webMethodName, params, fakeRequest );
                Object tlsOutput = webToTlsOutput( baseMethodName, webOutput );
                result = SampResult.createSuccessResult( tlsOutput );
            }
            catch ( Throwable e ) {
                result = SampResult.createErrorResult( e.toString() );
            }
        }
        else {
            result = SampResult
                    .createErrorResult( "Unknown method " + callStr );
        }
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
        if ( "register".equals( baseMethodName ) &&
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
     * Returns a dummy HttpServer.Request object.
     *
     * @param   call  call from which request is supposed to have come
     * @parma   relayUrl   URL of hub relay
     * @return   fake request object
     */
    private static HttpServer.Request createFakeRequest( SampCall call,
                                                         URL relayUrl ) {
        Map fakeHeaderMap = new LinkedHashMap();
        if ( relayUrl != null ) {
            fakeHeaderMap.put( TlsAuthHeaderControl.RELAY_HDR,
                               relayUrl.toString() );
        }
        Object referer = call.get( SampCall.REFERER_KEY );
        if ( referer instanceof String ) {
            fakeHeaderMap.put( TlsAuthHeaderControl.REFERER_HDR,
                               (String) referer );
        }
        return new HttpServer.Request( null, null, fakeHeaderMap, null, null );
    }

    /**
     * Makes sense of a partial URL with parameters.
     */
    private static class ParsedUrl {
        final String path_;
        private final Map<String,String> params_;
        private static final String ENC = "UTF-8";

        /**
         * Constructor.
         *
         * @param   localPart   local part of URL
         */
        ParsedUrl( String localPart ) {
            int iq = localPart.indexOf( '?' ); 
            final String query;
            if ( iq >= 0 ) {
                path_ = urlDecode( localPart.substring( 0, iq ) );
                query = localPart.substring( iq + 1 );
            }
            else {
                path_ = localPart;
                query = "";
            }
            params_ = new LinkedHashMap<String,String>();
            if ( query.trim().length() > 0 ) {
                for ( String paramPart : query.split( "&" ) ) {
                    int ie = paramPart.indexOf( '=' );
                    final String key;
                    final String value;
                    if ( ie >= 0 ) {
                        key = paramPart.substring( 0, ie );
                        value = paramPart.substring( ie + 1 );
                    }
                    else {
                        key = paramPart;
                        value = "";
                    }
                    params_.put( urlDecode( key ), urlDecode( value ) );
                }
            }
        }

        /**
         * Returns the URL of the remote hub relay service, as encoded in
         * this object.
         *
         * @return   remote URL for message retrieval, or null
         */
        URL getRelayUrl() {
            String relayUrl = params_.get( RELAYURL_PARAM );
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
            return params_.get( CALLTAG_PARAM );
        }

        /**
         * Returns true if this is to be interpreted as an initialisation
         * (no request implicit).
         *
         * @return  true if this is an initialisation call
         */
        public boolean isInit() {
            return ! params_.containsKey( RELAYURL_PARAM );
        }

        /**
         * Decodes text.
         *
         * @param   txt  input string
         * @return   unescaped text
         */
        private static final String urlDecode( String txt ) {
            String encoding = "UTF-8";
            try {
                return URLDecoder.decode( txt, encoding );
            }
            catch ( UnsupportedEncodingException e ) {
                logger_.warning( "Unsupported encoding " + encoding + "???" );
                return txt;
            }
        }
    }
}
