package org.astrogrid.samp.tls;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.JPanel;
import org.astrogrid.samp.httpd.HttpServer;

/**
 * Supplies HTTP responses containing little images.
 * Currently these are small circles, could be anything.
 * The web client may hide them in any case.
 * Used with TLS hub.
 *
 * @author   Mark Taylor
 * @since    11 Mar 2016
 */
class ImageResponse {

    /**
     * Returns an image response representing an on/off state.
     *
     * @param  isOn  true for on, false for off
     * @return  image-typed HTTP response
     */
    public static HttpServer.Response
            createToggleResponse( final boolean isOn ) {
        final int d = 12;
        final int s = 1;
        Icon icon = new Icon() {
            public int getIconWidth() {
                return d;
            }
            public int getIconHeight() {
                return d;
            }
            public void paintIcon( Component c, Graphics g, int x, int y ) {
                Color color0 = g.getColor();
                g.setColor( Color.GRAY );
                g.drawOval( x + s, x + s, d - 2 * s, d - 2 * s );
                if ( isOn ) {
                    g.fillOval( x + 4 * s, x + 4 * s, d - 7 * s, d - 7 * s );
                }
                g.setColor( color0 );
            }
        };
        return createImageResponse( icon );
    }

    /**
     * Returns an image response representing some kind of spinning wheel.
     *
     * @param  iseq  sequence number, incrementing it spins the wheel
     * @return  image-typed HTTP response
     */
    public static HttpServer.Response createSpinResponse( final int iseq ) {
        final int d = 12;
        Icon icon = new Icon() {
            public int getIconWidth() {
                return d;
            }
            public int getIconHeight() {
                return d;
            }
            public void paintIcon( Component c, Graphics g, int x, int y ) {
                Color color0 = g.getColor();
                g.setColor( Color.LIGHT_GRAY );
                g.fillOval( x, y, d, d );
                g.setColor( Color.DARK_GRAY );
                g.fillArc( x, y, d, d, ( -60 - iseq * 30 ) % 360, 60 );
                g.setColor( color0 );
            }
        };
        return createImageResponse( icon );
    }

    /**
     * Returns an image-typed HTTP response based on a given icon.
     * Some default MIME type is used.
     *
     * @param  icon   icon
     * @return  image-typed HTTP response
     */
    public static HttpServer.Response createImageResponse( Icon icon ) {
        return createImageResponse( icon, "PNG", "image/png" );
    }

    /**
     * Returns an HTTP response representing an image using a
     * given image format.
     *
     * @param  icon   icon
     * @param   fmtName   ImageIO format name
     * @param   mimeType  MIME type corresponding to fmtName
     * @return  HTTP response
     */
    public static HttpServer.Response
            createImageResponse( Icon icon, String fmtName, String mimeType ) {
        int w = icon.getIconWidth();
        int h = icon.getIconHeight();
        BufferedImage img =
            new BufferedImage( w, h, BufferedImage.TYPE_INT_ARGB );
        Graphics2D g2 = img.createGraphics();
        Color color0 = g2.getColor();
        g2.setColor( Color.WHITE );
        g2.fillRect( 0, 0, w, h );
        g2.setColor( color0 );
        RenderingHints hints = g2.getRenderingHints();
        g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING,
                             RenderingHints.VALUE_ANTIALIAS_ON );
        icon.paintIcon( new JPanel(), g2, 0, 0 );
        g2.setRenderingHints( hints );
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            OutputStream out = new BufferedOutputStream( bout );
            boolean done = ImageIO.write( img, fmtName, out );
            out.flush();
            if ( ! done ) {
                throw new IOException( "Unknown image format " + fmtName );
            }
            final byte[] buf = bout.toByteArray();
            Map hdrMap = new LinkedHashMap();
            hdrMap.put( "Content-Type", mimeType );
            hdrMap.put( "Content-Length", Integer.toString( buf.length ) );
            return new HttpServer.Response( 200, "OK", hdrMap ) {
                public void writeBody( OutputStream out ) throws IOException {
                    out.write( buf );
                }
            };
        }
        catch ( IOException e ) {
            return HttpServer.createErrorResponse( 500, "Server error", e );
        }
    }
}
