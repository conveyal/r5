package com.conveyal.osmlib.display;

import com.conveyal.osmlib.OSMEntitySink;
import com.conveyal.osmlib.OSMEntitySource;
import com.conveyal.osmlib.VexInput;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.ExecutionException;

class Surface extends JPanel implements ActionListener, MouseListener, MouseWheelListener, MouseMotionListener {

    private static final Logger LOG = LoggerFactory.getLogger(Display.class);

    final double METERS_PER_DEGREE_LAT = 111111.0;
    double metersPerPixel = 1000.0;
    double pixelsPerDegreeLat; // updated from the previous two fields

    Rectangle2D wgsWindow;

    private final int DELAY = 20;
    private Timer timer;

    // Drag
    int lastX = -1;
    int lastY = -1;

    // Local copy of tiles pulled from the web
    private LoadingCache<WebMercatorTile, BufferedImage> tileCache;

    // Advantage of doing the transform ourselves rather than using g2d affine transforms:
    // we avoid having to transform the stroke and can make 1px lines etc.
    // http://stackoverflow.com/questions/5046088/affinetransform-without-transforming-stroke

    public Surface() {
        this.addMouseListener(this);
        this.addMouseWheelListener(this);
        this.addMouseMotionListener(this);
        // wgsWindow = new Rectangle.Double(-73.8,40.61,0.05, 0.05); // NYC
        wgsWindow = new Rectangle.Double(-122.68,45.52,0.02,0.02);
        timer = new Timer(DELAY, this);
        //timer.start();
        tileCache = CacheBuilder.newBuilder().maximumSize(1000).build(
            new CacheLoader<WebMercatorTile, BufferedImage>() {
                @Override
                public BufferedImage load(WebMercatorTile tile) throws Exception {
                    try {
                        URL url = new URL(String.format(tileFormat, tile.zoom, tile.x, tile.y));
                        LOG.info("loading tile at {}", url);
                        BufferedImage image = ImageIO.read(url);
                        return image;
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        return null;
                    }
                }
            }
        );
    }

    public Timer getTimer() {
        return timer;
    }

    private static final String tileFormat = "http://a.tile.thunderforest.com/transport/%d/%d/%d.png";

    public void drawMapTiles (Graphics2D g2d) {
        final int zoom = 15;
        int minX = WebMercatorTile.xTile(wgsWindow.getMinX(), zoom);
        int maxY = WebMercatorTile.yTile(wgsWindow.getMinY(), zoom);
        int maxX = WebMercatorTile.xTile(wgsWindow.getMaxX(), zoom);
        int minY = WebMercatorTile.yTile(wgsWindow.getMaxY(), zoom); // min y is from max latitude.
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                WebMercatorTile tile = new WebMercatorTile(zoom, x, y);
                try {
                    // TODO if cache is not warm, place the tile drawing action in a queue or trigger
                    // redraw when it's loaded (perhaps on a timer).
                    // Thus, maybe a loadingCache is not the right tool here.
                    BufferedImage tileImage = tileCache.get(tile);
                    // TODO make a get rect convenience method on the tile
                    Rectangle2D box = WebMercatorTile.getRectangle(x, y, zoom);
                    // The last transform function called is the first to be applied to the image coordinate space.
                    // Use _max_ Y rather than min because we're going to flip the y coordinates
                    AffineTransform transform = AffineTransform.getTranslateInstance(box.getMinX(), box.getMaxY());
                    // Scale the image to the interval [0, widthDegrees) horizontally and [0, -heightDegrees) vertically.
                    transform.scale(box.getWidth() / 256, box.getHeight() / -256);
                    g2d.drawImage(tileImage, transform, null);
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /** Very crude test -- just fetch the entire window from VEX and draw the nodes. */
    public void drawOsm (Graphics2D g2d) {
        try {
            LOG.info("world window coordinates: {}", wgsWindow);
            String urlString = String.format("http://localhost:9001/%f,%f,%f,%f.vex",
                    wgsWindow.getMinY(),wgsWindow.getMinX(),wgsWindow.getMaxY(),wgsWindow.getMaxX());
            URL url = new URL(urlString);
            InputStream vexStream = url.openStream();
            OSMEntitySink graphicsSink = new GraphicsSink(g2d);
            OSMEntitySource vexSource = new VexInput(vexStream);
            vexSource.copyTo(graphicsSink);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);
        // TODO paint async outside the paint function?

        Graphics2D g2d = (Graphics2D) g;
        AffineTransform savedTransform = g2d.getTransform();
        // Apply transforms on top of screen coords. Last specified => first applied.
        pixelsPerDegreeLat = this.getHeight() / wgsWindow.getHeight();
        g2d.scale(pixelsPerDegreeLat, -pixelsPerDegreeLat); // TODO cos scaling, tricky
        // Subtract _max_ Y because we're going to flip the coordinate system vertically
        g2d.translate(-wgsWindow.getMinX(), -wgsWindow.getMaxY());
        //g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        //g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setPaint(Color.blue);
        g2d.setStroke(new BasicStroke(0.0001f));
        //g2d.setStroke(new BasicStroke(2));
        drawMapTiles(g2d);
        drawOsm(g2d); // SLOW
        g2d.setTransform(savedTransform);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        repaint();
    }

    @Override
    public void mouseClicked(MouseEvent e) {
    }

    @Override
    public void mousePressed(MouseEvent e) {
        lastX = e.getX();
        lastY = e.getY();
    }

    @Override
    public void mouseReleased(MouseEvent e) {

    }

    @Override
    public void mouseEntered(MouseEvent e) {

    }

    @Override
    public void mouseExited(MouseEvent e) {

    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        int x = e.getX();
        int y = e.getY();
        // TODO metersPerPixel *= (1 + (0.1 * e.getWheelRotation()));
        double scale = 1 + (0.05 * e.getWheelRotation());
        wgsWindow.setRect(wgsWindow.getX(), wgsWindow.getY(),
                wgsWindow.getWidth() * scale, wgsWindow.getHeight() * scale);
        repaint();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        int dx = e.getX() - lastX;
        int dy = e.getY() - lastY;
        double newLon = wgsWindow.getMinX() - dx / pixelsPerDegreeLat;
        double newLat = wgsWindow.getMinY() + dy / pixelsPerDegreeLat;
        wgsWindow.setRect(newLon, newLat, wgsWindow.getWidth(), wgsWindow.getHeight());
        lastX = e.getX();
        lastY = e.getY();
        repaint();
    }

    @Override
    public void mouseMoved(MouseEvent e) {

    }
}

public class Display extends JFrame {

    public Display() {
        initUI();
    }

    private void initUI() {
        final Surface surface = new Surface();
        this.add(surface);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                Timer timer = surface.getTimer();
                timer.stop();
            }
        });
        setTitle("Points");
        setSize(350, 250);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    public static void main(String[] args) {
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                Display ex = new Display();
                ex.setVisible(true);
            }
        });
    }

}
