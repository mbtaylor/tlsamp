package org.astrogrid.samp.tls;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
import org.astrogrid.samp.web.HubSwingClientAuthorizer;
import org.astrogrid.samp.web.ListMessageRestriction;
import org.astrogrid.samp.web.UrlTracker;
import org.astrogrid.samp.web.WebClientProfile;
import org.astrogrid.samp.web.WebHubXmlRpcHandler;
import org.astrogrid.samp.xmlrpc.SampXmlRpcClient;
import org.astrogrid.samp.xmlrpc.SampXmlRpcClientFactory;
import org.astrogrid.samp.xmlrpc.XmlRpcKit;

public class TlsHubProfile implements HubProfile {

    private final int port_;
    private final ClientAuthorizer auth_;
    private final MessageRestriction mrestrict_;
    private final SampXmlRpcClientFactory xClientFactory_;
    private final KeyGenerator keyGen_;
    private final ConcurrentMap<URL,Jobber> jobMap_;
    private ExecutorService callExecutor_;
    private ExecutorService collectorExecutor_;
    private WebHubXmlRpcHandler wxHandler_;
    private HttpServer hServer_;
    private static final Logger logger_ =
        Logger.getLogger( TlsHubProfile.class.getName() );

    public static final int TLSAMP_PORT = 21013;
    public static final String COLLECT_PATH = "/collect";
    public static final String BOUNCEURL_PARAM = "bouncer";
    public static final String DISPENSER_PREFIX = "samp.tlsfwd.";
    private static final int TIMEOUT_SEC = 10;

    public TlsHubProfile( int port, ClientAuthorizer auth,
                          MessageRestriction mrestrict,
                          SampXmlRpcClientFactory xClientFactory,
                          KeyGenerator keyGen ) {
        port_ = port;
        auth_ = auth;
        mrestrict_ = mrestrict;
        xClientFactory_ = xClientFactory;
        keyGen_ = keyGen;
        jobMap_ = new ConcurrentHashMap<URL,Jobber>();
    }

    public TlsHubProfile() {
        this( TLSAMP_PORT, new HubSwingClientAuthorizer( null ),
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
        hServer_.addHandler( new CollectHandler() );
        URL baseUrl = hServer_.getBaseUrl();  // not sure about that
        UrlTracker urlTracker = null;  // not sure about that
        wxHandler_ = new WebHubXmlRpcHandler( clientProfile, auth_, keyGen_,
                                              baseUrl, urlTracker );
        callExecutor_ = Executors.newCachedThreadPool( new ThreadFactory() {
            public Thread newThread( Runnable r ) {
                return new Thread( r, "TLS-SAMP_bounced_call_invoker" );
            }
        } );
        collectorExecutor_ = callExecutor_;
        jobMap_.clear();
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
        jobMap_.clear();
        hServer_ = null;
        wxHandler_ = null;
    }

    public synchronized boolean isRunning() {
        return hServer_ != null;
    }

    public MessageRestriction getMessageRestriction() {
        return mrestrict_;
    }

    private class CollectHandler implements HttpServer.Handler {
        private int iseq_;
        public HttpServer.Response serveRequest( HttpServer.Request request ) {
            String method = request.getMethod();
            ParsedUrl pu = new ParsedUrl( request.getUrl() );
            String path = pu.path_;
            URL bouncerUrl = pu.getBouncerUrl();
            if ( COLLECT_PATH.equals( path ) ) {
                if ( ! "GET".equals( method ) ) {
                    return HttpServer
                          .create405Response( new String[] { "GET" } );
                }
                else if ( pu.isInit() ) {
                    return ImageResponse.createToggleResponse( false );
                }
                else if ( bouncerUrl == null ) {
                    return HttpServer
                          .createErrorResponse( 400, "Bad tls-samp params" );
                }
                logger_.info( "Collect messages from " + bouncerUrl );
                Jobber jobber = getJobber( bouncerUrl );
                jobber.submitJob();
                collectCalls( bouncerUrl, jobber );
                HttpServer.Response response =
                    ImageResponse.createSpinResponse( iseq_++ );
                response.getHeaderMap().put( "Cache-Control", "no-cache" );
                return response;
            }
            else {
                return null;
            }
        }
    }

    private Jobber getJobber( URL url ) {
        jobMap_.putIfAbsent( url, new Jobber( TIMEOUT_SEC * 1000 ) );
        return jobMap_.get( url );
    }

    private void collectCalls( final URL bouncerUrl, final Jobber jobber ) {
        if ( jobber.startJob() ) {
            try {
                collectorExecutor_.execute( new Runnable() {
                    public void run() {
                        try {
                            doCollectCalls( bouncerUrl, TIMEOUT_SEC );
                        }
                        catch ( ConnectException e ) {
                            logger_.log( Level.WARNING,
                                         "No hub proxy at " + bouncerUrl );
                            return;
                        }
                        catch ( Throwable e ) {
                            logger_.log( Level.WARNING, "Call collection error",
                                         e );
                            return;
                        }
                        finally {
                            jobber.jobCompleted();
                        }
                        collectCalls( bouncerUrl, jobber );
                    }
                } );
            }
            catch ( RejectedExecutionException e ) {
                jobber.jobCompleted();
            }
        }
    }

    private void doCollectCalls( URL bouncerUrl, int timeoutSec )
            throws IOException {
        final SampXmlRpcClient xClient =
            xClientFactory_.createClient( bouncerUrl );
        String timeoutStr = SampUtils.encodeInt( timeoutSec );
        List<?> pullParams = Collections.singletonList( timeoutStr );
        Object pulled =
            xClient.callAndWait( DISPENSER_PREFIX + "pullCalls", pullParams );
        if ( pulled instanceof List ) {
            List<?> pulledList = (List) pulled;
            int nitem = pulledList.size();
            int iitem = 0;
            for ( Object pulledItem : pulledList ) {
                if ( pulledItem instanceof Map ) {
                    final SampCall call = SampCall.asCall( (Map) pulledItem );
                    final String msg = "Handle call " + (++iitem) + "/" + nitem;
                    try {
                        callExecutor_.execute( new Runnable() {
                            public void run() {
                                logger_.info( msg );
                                handleCall( xClient, call );
                            }
                        } );
                    }
                    catch ( RejectedExecutionException e ) {
                        logger_.log( Level.WARNING,
                                     "Can't execute pulled call " + call, e );
                    }
                }
                else {
                    logger_.warning( "Pulled call was not a SAMP map" );
                }
            }
        }
        else {
            logger_.warning( "Pulled call result was not a SAMP list" );
        }
    }

    private void handleCall( SampXmlRpcClient xClient, SampCall call ) {
        String callOp = call.getOperationName();
        logger_.info( "Handling bounced call " + callOp );
        SampResult result;
        if ( wxHandler_.canHandleCall( callOp ) ) {
            List callParams = call.getParams();
            HttpServer.Request fakeRequest = createFakeRequest();
            try {
                Object output =
                    wxHandler_.handleCall( callOp, callParams, fakeRequest );
                result = SampResult.createSuccessResult( output );
            }
            catch ( Throwable e ) {
                result = SampResult.createErrorResult( e.toString() );
            }
        }
        else {
            result = SampResult.createErrorResult( "Unknown operation "
                                                 + "\"" + callOp + "\"" );
        }
        String tag = call.getCallTag();
        List resultParams = Arrays.asList( new Object[] { tag, result } );
        try {
            xClient.callAndWait( DISPENSER_PREFIX + "acceptResult",
                                 resultParams );
        }
        catch ( IOException e ) {
            logger_.log( Level.WARNING,
                         "Failed to pass result back for " + tag, e );
        }
    }

    private static HttpServer.Request createFakeRequest() {
        Map fakeHeaderMap = new LinkedHashMap();
        fakeHeaderMap.put( "Origin", "fake-origin" );
        return new HttpServer.Request( null, null, fakeHeaderMap, null, null );
    }

    private static class ParsedUrl {
        final String path_;
        final Map<String,String> params_;
        private static final String ENC = "UTF-8";

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

        URL getBouncerUrl() {
            String bounceUrl = params_.get( BOUNCEURL_PARAM );
            if ( bounceUrl == null ) {
                return null;
            }
            try {
                return new URL( bounceUrl );
            }
            catch ( MalformedURLException e ) {
                logger_.warning( "Bouncer URL not URL (" + bounceUrl + ")" );
                return null;
            }
        }

        public boolean isInit() {
            return params_.size() == 0;
        }

        private static final String urlDecode( String txt ) {
            String encoding = "UTF-8";
            try {
                return URLDecoder.decode( txt, encoding );
            }
            catch ( UnsupportedEncodingException e ) {
                logger_.warning( "Unsuppored encoding " + encoding + "???" );
                return txt;
            }
        }
    }

    private static class Jobber {
        private final int lagMillis_;
        private long latestSubmitTime_;
        private boolean executing_;
        Jobber( int lagMillis ) {
            lagMillis_ = lagMillis;
            latestSubmitTime_ = Long.MIN_VALUE;
        }
        public synchronized void submitJob() {
            latestSubmitTime_ = System.currentTimeMillis();
        }
        public synchronized boolean startJob() {
            if ( executing_ ) {
                return false;
            }
            else if ( System.currentTimeMillis() - latestSubmitTime_
                      < lagMillis_ ) {
                executing_ = true;
                return true;
            }
            else {
                return false;
            }
        }
        public synchronized void jobCompleted() {
            executing_ = false;
        }
    }
}
