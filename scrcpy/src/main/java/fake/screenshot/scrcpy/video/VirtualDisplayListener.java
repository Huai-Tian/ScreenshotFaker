package fake.screenshot.scrcpy.video;

import fake.screenshot.scrcpy.control.PositionMapper;

public interface VirtualDisplayListener {
    void onNewVirtualDisplay(int displayId, PositionMapper positionMapper);
}
