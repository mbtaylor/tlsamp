package org.astrogrid.samp.tls;

import org.astrogrid.samp.hub.Hub;

/**
 * Small harness class that can be used to invoke topcat so that
 * the hub it starts includes the TLS profile.
 * Uses reflection so that it doesn't require topcat classes at
 * compile time.  Exceptions are not handled carefully, not suitable
 * for production code.
 */
public class TlsTopcat {
    public static void main( String[] args ) throws Exception {
        System.setProperty( Hub.HUBPROFILES_PROP,
                            "std,web," + TlsHubProfile.class.getName() );
        Class.forName( "uk.ac.starlink.topcat.Driver" )
             .getMethod( "main", String[].class )
             .invoke( null, new Object[] { args } );
    }
}
