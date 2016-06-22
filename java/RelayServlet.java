package org.astrogrid.samp.tls;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilderFactory;
import org.astrogrid.samp.xmlrpc.SampXmlRpcHandler;
import org.astrogrid.samp.xmlrpc.internal.InternalServer;
import org.astrogrid.samp.xmlrpc.internal.XmlRpcCall;
import org.astrogrid.samp.xmlrpc.internal.XmlUtils;
import org.w3c.dom.Document;

/**
 * Servlet harness for the TLS hub relay functionality.
 *
 * @author   Mark Taylor
 * @since    11 Mar 2016
 */
public class RelayServlet extends HttpServlet {

    private static final HttpRequestFormat reqFormat_ =
        new ServletHttpRequestFormat();
    private SampXmlRpcHandler receiveHandler_;
    private SampXmlRpcHandler dispenseHandler_;
    private DocumentBuilderFactory dbFact_;
    private final boolean checkHostnames_;
    private static final String RELAY_ATTNAME =
        XmlRpcRelay.class.getName();
    private static final String DBFACT_ATTNAME =
        RelayServlet.class.getName() + ".dbfact";

    /**
     * Constructor.
     */
    public RelayServlet() {
        checkHostnames_ = true;
    }

    @Override
    public void init( ServletConfig config ) throws ServletException {
        super.init( config );
        ServletContext context = config.getServletContext();

        // It might be more respectable to declare the RelayContextInitializer
        // class where the servlet container will pick it up.
        // However, I'm not sure where to do that, and doing it like this
        // is at least transparent.
        if ( context.getAttribute( RELAY_ATTNAME ) == null ) {
            new RelayContextInitializer( checkHostnames_ )
               .contextInitialized( new ServletContextEvent( context ) );
        }

        // Initialise persistent state for this servlet.
        Object dbfactObj = context.getAttribute( DBFACT_ATTNAME );
        Object relayObj = context.getAttribute( RELAY_ATTNAME );
        if ( dbfactObj instanceof DocumentBuilderFactory &&
             relayObj instanceof XmlRpcRelay ) {
            dbFact_ = (DocumentBuilderFactory) dbfactObj;
            XmlRpcRelay relay = (XmlRpcRelay) relayObj;
            receiveHandler_ = relay.getReceiveHandler();
            dispenseHandler_ = relay.getDispenseHandler();
        }
        else {
            throw new ServletException( "Init failed" );
        }
    }

    @Override
    protected void doGet( HttpServletRequest req, HttpServletResponse resp )
            throws IOException {

        // Emit a message, but this doesn't support GET in a meaningful way,
        // since XML-RPC is only defined for POST.
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.setContentType( "text/plain" );
        PrintStream out = new PrintStream( resp.getOutputStream() );
        out.println( "This is an XML-RPC servlet.  You should POST to it." );
        out.flush();
    }

    @Override
    protected void doPost( HttpServletRequest req, HttpServletResponse resp )
            throws IOException {
        byte[] outBytes;
        try {
            outBytes = getResponseBytes( req );
        }
        catch ( Exception e ) {
            outBytes = InternalServer.getFaultBytes( e );
        }
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.setContentLength( outBytes.length );
        resp.setContentType( "text/xml" );
        OutputStream out = resp.getOutputStream();
        out.write( outBytes );
        out.flush();
    }

    /**
     * This method does the work for handling an XML-RPC request and
     * generating a non-error response.  It turns an HTTP request into
     * the content of an HTTP response.
     *
     * @param  req  request
     * @return   byte content for XML response
     */
    private byte[] getResponseBytes( HttpServletRequest req ) throws Exception {
        Document doc = dbFact_.newDocumentBuilder()
                      .parse( req.getInputStream() );
        XmlRpcCall call = XmlRpcCall.createCall( doc );
        String methodName = call.getMethodName();
        List params = call.getParams();
        SampXmlRpcHandler handler = getHandler( methodName );
        if ( handler == null ) {
            throw new IllegalArgumentException( "No such method "
                                              + methodName );
        }
        Object result = handler.handleCall( methodName, params, req );
        return InternalServer.getResultBytes( result );
    }

    /**
     * Identifies the correct XML-RPC handler for a given XML-RPC method name.
     *
     * @param  methodName  XML-RPC methodName element content
     * @return   handler for named method, or null if none known
     */
    private SampXmlRpcHandler getHandler( String methodName ) {
        for ( SampXmlRpcHandler h :
              Arrays.asList( new SampXmlRpcHandler[] { receiveHandler_,
                                                       dispenseHandler_ } ) ) {
            if ( h.canHandleCall( methodName ) ) {
                return h;
            }
        }
        return null;
    }

    /**
     * HttpRequestFormat implementation for servlet framework.
     */
    private static class ServletHttpRequestFormat implements HttpRequestFormat {

        public String getHostName( Object reqObj ) {
            if ( reqObj instanceof HttpServletRequest ) {

                // We need to return the host name for the host at which
                // the XML-RPC request originated.  Pull it out from
                // the incoming HttpServletRequest object.
                // That sounds OK, but there may be a problem -
                // if the request has been through an HTTP proxy,
                // this address might be the wrong one
                // (see ServletRequest.getRemoteAddr javadocs).
                // So, maybe we need to identify originating calls by
                // tokens explicitly inserted by clients instead.  Hmm.
                return ((HttpServletRequest) reqObj).getRemoteAddr();
            }
            else {
                return null;
            }
        }

        public String getHeader( Object reqObj, String hdrName ) {
            return reqObj instanceof HttpServletRequest
                 ? ((HttpServletRequest) reqObj).getHeader( hdrName )
                 : null;
        }
    }

    /**
     * An instance of this class should be used for initialising the
     * persistent context for this servlet implementation.
     *
     * <p>If that's not set up (at time of writing I haven't worked out
     * how to do it), the contextInitialized method can be called lazily
     * instead.
     */
    private static class RelayContextInitializer
            implements ServletContextListener {
        private final boolean checkHostnames_;
        RelayContextInitializer( boolean checkHostnames ) {
            checkHostnames_ = checkHostnames;
        }
        public void contextInitialized( ServletContextEvent evt ) {
            ServletContext context = evt.getServletContext();
            context.setAttribute( RELAY_ATTNAME,
                                  new XmlRpcRelay( reqFormat_,
                                                   checkHostnames_ ) );
            context.setAttribute( DBFACT_ATTNAME,
                                  DocumentBuilderFactory.newInstance() );
        }
        public void contextDestroyed( ServletContextEvent evt ) {
        }
    }
}
