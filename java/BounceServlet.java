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

public class BounceServlet extends HttpServlet {

    private SampXmlRpcHandler receiveHandler_;
    private SampXmlRpcHandler dispenseHandler_;
    private DocumentBuilderFactory dbFact_;
    private static final String BOUNCER_ATTNAME =
        XmlRpcBouncer.class.getName();
    private static final String DBFACT_ATTNAME =
        BounceServlet.class.getName() + ".dbfact";

    @Override
    public void init( ServletConfig config ) throws ServletException {
        super.init( config );
        ServletContext context = config.getServletContext();

        // Should really handle this by declaring the BounceContextInitializer
        // class, but not sure where.
        if ( context.getAttribute( BOUNCER_ATTNAME ) == null ) {
            new BounceContextInitializer()
               .contextInitialized( new ServletContextEvent( context ) );
        }
        Object dbfactObj = context.getAttribute( DBFACT_ATTNAME );
        Object bouncerObj = context.getAttribute( BOUNCER_ATTNAME );
        if ( dbfactObj instanceof DocumentBuilderFactory &&
             bouncerObj instanceof XmlRpcBouncer ) {
            dbFact_ = (DocumentBuilderFactory) dbfactObj;
            XmlRpcBouncer bouncer = (XmlRpcBouncer) bouncerObj;
            receiveHandler_ = bouncer.getReceiveHandler();
            dispenseHandler_ = bouncer.getDispenseHandler();
        }
        else {
            throw new ServletException( "Init failed" );
        }
    }

    @Override
    protected void doGet( HttpServletRequest req, HttpServletResponse resp )
            throws IOException {
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

    public static XmlRpcBouncer createBouncer() {
        return new XmlRpcBouncer( new Random() ) {
            public String getHostName( Object reqObj ) {
                if ( reqObj instanceof HttpServletRequest ) {
                    // uh oh - could be a proxy
                    return ((HttpServletRequest) reqObj).getRemoteAddr();
                }
                else {
                    return null;
                }
            }
        };
    }

    private static class BounceContextInitializer
            implements ServletContextListener {
        public void contextInitialized( ServletContextEvent evt ) {
            ServletContext context = evt.getServletContext();
            context.setAttribute( BOUNCER_ATTNAME, createBouncer() );
            context.setAttribute( DBFACT_ATTNAME,
                                  DocumentBuilderFactory.newInstance() );
        }
        public void contextDestroyed( ServletContextEvent evt ) {
        }
    }
}
