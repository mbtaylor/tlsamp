package org.astrogrid.samp.tls;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.astrogrid.samp.hub.Hub;

/**
 * Small harness class to run the JSAMP hub with the TLS Profile
 * installed by default.  Invoke it just like jsamp hub.
 */
public class TlsHub {
    public static void main( String[] args ) {
        if ( ! Arrays.asList( args ).contains( "-profiles" ) ) {
            List<String> argList = new ArrayList( Arrays.asList( args ) );
            argList.add( "-profiles" );
            argList.add( "std,web," + TlsHubProfile.class.getName() );
            args = argList.toArray( new String[ 0 ] );
        }
        Hub.main( args );
    }
}
