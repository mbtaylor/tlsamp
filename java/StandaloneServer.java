package org.astrogrid.samp.tls;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URL;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import org.astrogrid.samp.httpd.DirectoryMapperHandler;
import org.astrogrid.samp.httpd.HttpServer;
import org.astrogrid.samp.xmlrpc.SampXmlRpcServer;
import org.astrogrid.samp.xmlrpc.internal.InternalServer;
import org.astrogrid.samp.tls.XmlRpcBouncer;

/**
 * This is a standalone HTTP server that can serve documents available
 * on the JVM's class path and run a TLS-SAMP proxy hub.
 *
 * @author   Mark Taylor
 * @since    11 Mar 2016
 */
public class StandaloneServer {

    private final HttpServer hServer_;

    /**
     * Constructor.
     *
     * @param   ssock  unbound server socket
     * @param   port   port to bind to
     * @param   proxyHubPath   server endpoint for proxy hub XML-RPC server;
     *                       if null, proxy hub is not run
     * @param   localDocBase    path prefix on JVM's classpath at which
     *                          servable documents are located
     * @param   serverDocPath   server endpoint for servable documents
     */
    public StandaloneServer( ServerSocket ssock, int port, String proxyHubPath,
                             String localDocBase, String serverDocPath )
            throws IOException {
        Logger.getLogger( getClass().getName() )
              .warning( "Running "
                      + ( ssock instanceof SSLServerSocket ? "HTTPS" : "HTTP" )
                      + " server on port " + port );
        ssock.setReuseAddress( true );
        ssock.bind( new InetSocketAddress( port ) );
        hServer_ = new HttpServer( ssock );
        hServer_.setDaemon( false );
        hServer_.addHandler( new DirectoryMapperHandler( localDocBase,
                                                         serverDocPath ) );
        if ( proxyHubPath != null ) {
            SampXmlRpcServer xServer =
                    new InternalServer( hServer_, proxyHubPath );
            XmlRpcBouncer bouncer = new XmlRpcBouncer( new Random( 890223 ) ) {
                public String getHostName( Object reqObj ) {
                    if ( reqObj instanceof HttpServer.Request ) {
                        SocketAddress saddr =
                            ((HttpServer.Request) reqObj).getRemoteAddress();
                        if ( saddr instanceof InetSocketAddress ) {
                            InetSocketAddress naddr = (InetSocketAddress) saddr;
                            return naddr.getHostName();
                        }
                    }
                    return null;
                }
                public String getHeader( Object reqObj, String hdrName ) {
                    return reqObj instanceof HttpServer.Request
                         ? HttpServer.getHeader( ((HttpServer.Request) reqObj)
                                                .getHeaderMap(),
                                                 hdrName )
                         : null;
                }
            };
            xServer.addHandler( bouncer.getReceiveHandler() );
            xServer.addHandler( bouncer.getDispenseHandler() );
        }
    }

    /**
     * Starts this server running.
     */
    public void start() {
        hServer_.start();
    }

    /**
     * Main method.  Runs two servers serving the same documents
     * (could be web samp clients).
     */
    public static void main( String[] args ) throws IOException {
        Logger.getLogger( "org.astrogrid.samp" ).setLevel( Level.INFO );
        String localDocBase = "/resources";
        String serverDocPath = "/docs";
        String proxyHubPath = serverDocPath + "/xmlrpc";

        // HTTP server: just serves documents.  Will work with normal
        // web profile.  If you set proxyHubPath to the non-null value,
        // it would run an HTTP proxy hub.  There's no reason you
        // need this, because in HTTP mode you don't need a proxy hub,
        // but you could use it for testing if you can't get HTTPS
        // to work (e.g. if you have no certificates).
        StandaloneServer httpServer =
            new StandaloneServer( new ServerSocket(),
                                  2113, null, localDocBase, serverDocPath );

        // HTTPS server: serves documents and proxy hub.
        // Needs TLS profile hub on client's host.
        StandaloneServer httpsServer =
            new StandaloneServer( SSLServerSocketFactory.getDefault()
                                                        .createServerSocket(),
                                  2112, proxyHubPath,
                                  localDocBase, serverDocPath );
        httpServer.start();
        httpsServer.start();
    }
}
