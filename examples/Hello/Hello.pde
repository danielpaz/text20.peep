import eyetracking.*;

// This is our tracking device. It provides you with eye data.
EyeTrackingDevice device;

void setup() {
    // Opening the device like this should be fine. Takes ~5 secs to warm-up.
    device = EyeTrackingDevice.open(this);
}


void draw() {
    // Call this method to get the current status    
    device.debug();
    
    // Print if the user is looking at the application 
    println(device.isLooking);  
    
    // Print where he's looking at
    println(device.x);  
    println(device.y);  
}