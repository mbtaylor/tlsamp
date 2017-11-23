package org.astrogrid.samp.tls;

/**
 * Understands the internals of an object representing an HTTP request.
 * Different server frameworks can represent these objects in different
 * ways, but this provides a facade to extract the interesting information
 * from them.
 *
 * <p>The request object is the final parameter passed to the
 * {@link org.astrogrid.samp.xmlrpc.SampXmlRpcHandler#handleCall handleCall}
 * method; its nature depends on the XmlRpc implementation within
 * which this relay is being harnessed.
 *
 * @author   Mark Taylor
 * @since    22 Jun 2016
 */
public interface HttpRequestFormat {

    /**
     * Returns a host identifier string given a request object.
     *
     * @param  reqInfo   information about an HTTP request
     * @return  hostname of request originator
     */
    String getHostName( Object reqInfo );

    /**
     * Returns the value of an HTTP header, if known, given a request object.
     *
     * @param  reqInfo  information about an HTTP request
     * @param  headerName   name of HTTP header, case insensitive
     * @return  value for given header in request, or null if not present
     */
    public abstract String getHeader( Object reqInfo, String headerName );
}
