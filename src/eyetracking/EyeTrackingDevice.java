/*
  Processing Simple Eye Tracking Library
  
  (c) 2010, Ralf Biedert
  
  This library is free software; you can redistribute it and/or
  modify it under the terms of the GNU Lesser General Public
  License as published by the Free Software Foundation; either
  version 2.1 of the License, or (at your option) any later version.

  This library is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
  Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General
  Public License along with this library; if not, write to the
  Free Software Foundation, Inc., 59 Temple Place, Suite 330,
  Boston, MA  02111-1307  USA
 */

package eyetracking;

import java.awt.Dimension;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import net.xeoh.plugins.base.PluginManager;
import net.xeoh.plugins.base.impl.PluginManagerFactory;
import net.xeoh.plugins.base.util.JSPFProperties;
import net.xeoh.plugins.base.util.uri.ClassURI;
import net.xeoh.plugins.informationbroker.impl.InformationBrokerImpl;
import net.xeoh.plugins.meta.updatecheck.UpdateCheck;
import net.xeoh.plugins.meta.updatecheck.impl.UpdateCheckImpl;
import net.xeoh.plugins.remote.impl.lipermi.RemoteAPIImpl;
import net.xeoh.plugins.remotediscovery.impl.v4.RemoteDiscoveryImpl;
import processing.core.PApplet;
import de.dfki.km.text20.services.evaluators.gaze.GazeEvaluator;
import de.dfki.km.text20.services.evaluators.gaze.GazeEvaluatorManager;
import de.dfki.km.text20.services.evaluators.gaze.impl.GazeEvaluatorManagerImpl;
import de.dfki.km.text20.services.evaluators.gaze.impl.handler.fixation.v1.FixationHandlerFactory;
import de.dfki.km.text20.services.evaluators.gaze.listenertypes.fixation.FixationEvent;
import de.dfki.km.text20.services.evaluators.gaze.listenertypes.fixation.FixationEventType;
import de.dfki.km.text20.services.evaluators.gaze.listenertypes.fixation.FixationListener;
import de.dfki.km.text20.services.trackingdevices.eyes.EyeTrackingDeviceProvider;
import de.dfki.km.text20.services.trackingdevices.eyes.EyeTrackingEvent;
import de.dfki.km.text20.services.trackingdevices.eyes.EyeTrackingListener;
import de.dfki.km.text20.services.trackingdevices.eyes.impl.trackingserver.TrackingServerDeviceProviderImpl;

/**
 * Entry point to our eye tracking library.
 * 
 * @author Ralf Biedert
 * 
 */
public class EyeTrackingDevice {

    /** Do we need this? */
    final PApplet myParent;

    /** Do we need this? */
    private final String VERSION = "1.3.0";

    /** The core(tm) */
    final PluginManager pluginManager;

    /** Prevents us from running setup twice */
    final AtomicBoolean setupComplete = new AtomicBoolean(false);

    /** Locks the precision object when it's being changed */
    final Lock precisionLock = new ReentrantLock();

    /** Our current precision data object */
    final PrecisionData currentPrecision = new PrecisionData();

    /** Current status debug string. */
    String currentStatus = "CURRENT STATUS UNSET";

    /** Current gaze position x coordinate (shorthand for eyes.currentFixationX) */
    public volatile int x = -1;

    /** Current gaze position y coordinate (shorthand for eyes.currentFixationY) */
    public volatile int y = -1;

    /** Current head position */
    public final Head head = new Head();

    /** Current head position */
    public final Eyes eyes = new Eyes();

    /** Current config */
    public final Config config = new Config();

    /** Only true if the person is really looking at the application window */
    public volatile boolean isLooking = false;

    /**
     * 
     * Create an eye tracking device.
     * 
     * @param theParent
     * @param instantSetup
     */
    private EyeTrackingDevice(PApplet theParent, PluginManager pm) {
        this.myParent = theParent;
        this.pluginManager = pm;

        this.currentStatus = "EyeTracking object successfully constructed. " + "Next setup() has to be called with the TrackingServer's IP " + "and port (like 'lipe://127.0.0.1:667'), or a discoverystring " + "has to be supplied (like 'discover://youngest'). If in doubt, " + "use the latter one.";
    }

    /**
     * Can be called to debug the current status.
     */
    public void debug() {
        System.out.println(this.currentStatus);
    }

    /**
     * Initializes the connection to the given device
     * 
     * @param string
     */
    public void setup(final String string) {
        if (this.setupComplete.get()) return;

        this.currentStatus = "Function setup() was called. If you can read " + "this there were problems spawning a Thread. Contact us " + "as this is an critical error. (Write to ralf.biedert@dfki.de)";

        // Setup in background. Otherwise we'll block very long (few seconds) while trying to locate a device.
        final Thread thread = new Thread(new Runnable() {

            @Override
            public void run() {
                EyeTrackingDevice.this.currentStatus = "Thread came up but no device was obtained yet. This ususally takes up " + "to five seconds. In case this message appears in, say, 10 seconds, Something is messed up.";

                final EyeTrackingDeviceProvider provider = EyeTrackingDevice.this.pluginManager.getPlugin(EyeTrackingDeviceProvider.class);
                final de.dfki.km.text20.services.trackingdevices.eyes.EyeTrackingDevice device = provider.openDevice(string);

                if (device == null) {
                    EyeTrackingDevice.this.currentStatus = "We were unable to find a TrackingServer. " + "This does NOT say that there was no eye tracker " + "found, but rather that the TrackingServer " + "which talks to the tracker wasn't there. If you haven't started it, start it, " + "if you have, try to make sure you have a network connection and cable plugged " + "in (most common source of error).";
                    System.out.println("Error opening device " + string + ". Most likely there is no TrackingServer running!");
                    return;
                }

                EyeTrackingDevice.this.currentStatus = "Device setup complete. Connecting handler. This step must not fail. (Write to ralf.biedert@dfki.de)";

                // Keeps the last head positions for smoothing
                final List<float[]> headPositions = new ArrayList<float[]>();
                final List<Point> gazePositions = new ArrayList<Point>();

                // Get raw head data
                device.addTrackingListener(new EyeTrackingListener() {

                    @Override
                    @SuppressWarnings("unused")
                    public void newTrackingEvent(EyeTrackingEvent arg0) {

                        // Upon a new tracking event, first check where we are on the screen
                        Point location = null;
                        Dimension size = new Dimension(0, 0);
                        try {
                            location = EyeTrackingDevice.this.myParent.frame.getLocationOnScreen();
                            size = EyeTrackingDevice.this.myParent.frame.getSize();
                        } catch (Exception e) {
                            //
                        }

                        // Keep a constant size for our average
                        if (headPositions.size() > EyeTrackingDevice.this.config.averagingHeadPositionSize)
                            headPositions.remove(0);

                        if (gazePositions.size() > EyeTrackingDevice.this.config.averagingRawGazeDataSize)
                            gazePositions.remove(0);

                        // Process head position
                        float[] headPosition = arg0.getHeadPosition();
                        float[] average = new float[3];

                        headPositions.add(headPosition);

                        // Compute average
                        for (float[] fs : headPositions) {
                            average[0] += fs[0];
                            average[1] += fs[1];
                            average[2] += fs[2];
                        }

                        for (int i = 0; i < average.length; i++) {
                            average[i] /= headPositions.size();
                        }

                        // Update our data
                        try {
                            EyeTrackingDevice.this.precisionLock.lock();
                            EyeTrackingDevice.this.currentPrecision.rawTime = arg0.getEventTime();
                            EyeTrackingDevice.this.head.x = average[0];
                            EyeTrackingDevice.this.head.y = average[1];
                            EyeTrackingDevice.this.head.z = average[2];

                            // Process eye positions
                            Point gazeCenter = arg0.getGazeCenter();
                            if (gazeCenter != null && gazeCenter.x > 0 && gazeCenter.y > 0) {
                                gazePositions.add(gazeCenter);
                                Point avg = new Point();

                                for (Point point : gazePositions) {
                                    avg.x += point.x;
                                    avg.y += point.y;
                                }

                                avg.x /= gazePositions.size();
                                avg.y /= gazePositions.size();

                                if (location != null) {
                                    EyeTrackingDevice.this.eyes.rawX = avg.x - location.x;
                                    EyeTrackingDevice.this.eyes.rawY = avg.y - location.y;

                                    EyeTrackingDevice.this.currentPrecision.rawX = EyeTrackingDevice.this.eyes.rawX;
                                    EyeTrackingDevice.this.currentPrecision.rawY = EyeTrackingDevice.this.eyes.rawY;
                                    EyeTrackingDevice.this.currentPrecision.rawValid = true;
                                } else {
                                    EyeTrackingDevice.this.currentPrecision.rawValid = false;
                                }
                            } else {
                                EyeTrackingDevice.this.currentPrecision.rawValid = false;
                            }

                        } finally {
                            EyeTrackingDevice.this.precisionLock.unlock();
                        }

                    }
                });

                // And gaze evaluation data
                final GazeEvaluatorManager gazeEvaluatorManager = EyeTrackingDevice.this.pluginManager.getPlugin(GazeEvaluatorManager.class);
                final GazeEvaluator gazeEvaluator = gazeEvaluatorManager.createEvaluator(device);

                gazeEvaluator.addEvaluationListener(new FixationListener() {

                    @Override
                    public void newEvaluationEvent(FixationEvent arg0) {
                        if (arg0.getType() != FixationEventType.FIXATION_START) return;
                        EyeTrackingDevice.this.currentStatus = "We received fixations. All is fine now :-).";

                        // Returns a position on the screen
                        final Point center = arg0.getFixation().getCenter();

                        // Convert it to the app window
                        Point location = null;
                        Dimension size = new Dimension(0, 0);

                        try {
                            EyeTrackingDevice.this.precisionLock.lock();
                            EyeTrackingDevice.this.currentPrecision.fixationTime = arg0.getGenerationTime();

                            location = EyeTrackingDevice.this.myParent.frame.getLocationOnScreen();
                            size = EyeTrackingDevice.this.myParent.frame.getSize();

                            // Check if there really is a location
                            if (location == null) {
                                // Can't be looking if there is no location on the screen
                                EyeTrackingDevice.this.isLooking = false;
                                EyeTrackingDevice.this.x = -1;
                                EyeTrackingDevice.this.y = -1;

                                EyeTrackingDevice.this.eyes.currentFixationX = -1;
                                EyeTrackingDevice.this.eyes.currentFixationY = -1;

                                EyeTrackingDevice.this.currentPrecision.fixationX = -1;
                                EyeTrackingDevice.this.currentPrecision.fixationY = -1;
                                EyeTrackingDevice.this.currentPrecision.fixationValid = false;

                                return;
                            }

                            EyeTrackingDevice.this.x = center.x - location.x;
                            EyeTrackingDevice.this.y = center.y - location.y;

                            EyeTrackingDevice.this.eyes.currentFixationX = EyeTrackingDevice.this.x;
                            EyeTrackingDevice.this.eyes.currentFixationY = EyeTrackingDevice.this.y;

                            EyeTrackingDevice.this.currentPrecision.fixationX = EyeTrackingDevice.this.x;
                            EyeTrackingDevice.this.currentPrecision.fixationY = EyeTrackingDevice.this.y;
                            EyeTrackingDevice.this.currentPrecision.fixationValid = true;

                            // Revoke our info in case its off on the right (lower) side
                            if (EyeTrackingDevice.this.x >= size.width || EyeTrackingDevice.this.y >= size.height || EyeTrackingDevice.this.x < 0 || EyeTrackingDevice.this.y < 0) {
                                EyeTrackingDevice.this.x = -1;
                                EyeTrackingDevice.this.y = -1;
                                EyeTrackingDevice.this.eyes.currentFixationX = -1;
                                EyeTrackingDevice.this.eyes.currentFixationY = -1;
                                EyeTrackingDevice.this.isLooking = false;

                                EyeTrackingDevice.this.currentPrecision.fixationX = -1;
                                EyeTrackingDevice.this.currentPrecision.fixationY = -1;
                                EyeTrackingDevice.this.currentPrecision.fixationValid = false;

                                return;
                            }

                            EyeTrackingDevice.this.isLooking = true;
                        } catch (Exception e) {
                             //
                         } finally {
                             EyeTrackingDevice.this.precisionLock.unlock();
                         }
                     }
                });

                EyeTrackingDevice.this.currentStatus = "Your setup appears fine; however, we haven't received " + "any fixations yet. Either nobody is looking at the screen, " + "or the tracker does not see you. In case you're using a simulator then something is probably broken. Did you put debug() inside a loop and wait long enough?";
                EyeTrackingDevice.this.setupComplete.set(true);
            }
        });
        thread.setDaemon(true);
        thread.start();

        this.currentStatus = "Function setup() completed, but the thread didn't come up. Critical error again. (Write to ralf.biedert@dfki.de)";
    }

    /**
     * return the version of the library.
     * 
     * @return String
     */
    public String version() {
        return this.VERSION;
    }

    /**
     * Returns the curren precision object
     * 
     * @return PrecisionData
     */
    public PrecisionData precisionData() {
        try {
            this.precisionLock.lock();
            return (PrecisionData) this.currentPrecision.clone();
        } finally {
            this.precisionLock.unlock();
        }
    }

    /**
     * Returns a device
     * 
     * @param applet
     * 
     * @return .
     */
    public static EyeTrackingDevice open(PApplet applet) {
        return open(applet, "discover://nearest");
    }

    /**
     * Returns a device
     * 
     * @param applet
     * 
     * @param address
     * 
     * @return .
     */
    public static EyeTrackingDevice open(PApplet applet, String address) {

        // Configure the plugin framework
        final JSPFProperties props = new JSPFProperties();
        props.setProperty(PluginManager.class, "logging.level", "OFF");
        props.setProperty(UpdateCheck.class, "update.url", "http://api.text20.net/common/versioncheck/");
        props.setProperty(UpdateCheck.class, "update.enabled", "true");
        props.setProperty(UpdateCheck.class, "product.name", "peep");
        props.setProperty(UpdateCheck.class, "product.version", "1.3");

        // Create plugin loader
        final PluginManager pm = PluginManagerFactory.createPluginManager(props);

        // Register plugins the really fast way
        pm.addPluginsFrom(new ClassURI(UpdateCheckImpl.class).toURI());
        pm.addPluginsFrom(new ClassURI(InformationBrokerImpl.class).toURI());
        pm.addPluginsFrom(new ClassURI(RemoteAPIImpl.class).toURI());
        pm.addPluginsFrom(new ClassURI(RemoteDiscoveryImpl.class).toURI());
        pm.addPluginsFrom(new ClassURI(TrackingServerDeviceProviderImpl.class).toURI());
        pm.addPluginsFrom(new ClassURI(GazeEvaluatorManagerImpl.class).toURI());
        pm.addPluginsFrom(new ClassURI(FixationHandlerFactory.class).toURI());

        // And spawn the module
        final EyeTrackingDevice device = new EyeTrackingDevice(applet, pm);

        if (address != null) device.setup(address);

        return device;
    }

    /**
     * @param args
     */
    public static void main(String[] args) {
        EyeTrackingDevice.open(null);
    }
}
