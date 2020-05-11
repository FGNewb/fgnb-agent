package com.daxiang.core.mobile.android.scrcpy;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.MultiLineReceiver;
import com.android.ddmlib.NullOutputReceiver;
import com.daxiang.App;
import com.daxiang.core.PortProvider;
import com.daxiang.core.mobile.android.AndroidDevice;
import com.daxiang.core.mobile.android.AndroidImgDataConsumer;
import com.daxiang.utils.IOUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Created by jiangyitao.
 */
@Slf4j
public class Scrcpy {

    private static final String LOCAL_SCRCPY_PATH = "vendor/scrcpy/scrcpy-server";
    private static final String REMOTE_SCRCPY_PATH = AndroidDevice.TMP_FOLDER + "/scrcpy-server.jar";

    private IDevice iDevice;
    private String mobileId;

    private int maxSize = 800;
    private int width;
    private int heigth;

    private int pid;

    private OutputStream controlOutputStream;

    private boolean isRunning = false;

    public Scrcpy(IDevice iDevice) {
        this.iDevice = iDevice;
        mobileId = iDevice.getSerialNumber();
    }

    public void setIDevice(IDevice iDevice) {
        this.iDevice = iDevice;
    }

    public synchronized void start(AndroidImgDataConsumer androidImgDataConsumer) throws Exception {
        Assert.notNull(androidImgDataConsumer, "dataConsumer cannot be null");

        if (isRunning) {
            return;
        }

        // 由于scrcpy启动后会删除设备里的scrcpy，所以每次都需要重新push
        // Scrcpy.server - Server.java unlinkSelf()
        pushScrcpyToDevice();

        CountDownLatch countDownLatch = new CountDownLatch(1);

        new Thread(() -> {
            try {
                String startCmd = "CLASSPATH=" + REMOTE_SCRCPY_PATH + " app_process / com.genymobile.scrcpy.Server " +
                        App.getProperty("scrcpyVersion") + " " +            // clientVersion
                        maxSize + " " +                                     // maxSize
                        App.getProperty("remoteScrcpyBitRate") + " " +      // bitRate
                        "60 " +                                             // maxFps >=android10才生效
                        "true " +                                           // tunnelForward
                        "- " +                                              // crop
                        "true " +                                           // sendFrameMeta
                        "true";                                             // control

                log.info("[scrcpy][{}]start: {}", mobileId, startCmd);
                iDevice.executeShellCommand(startCmd, new MultiLineReceiver() {
                    @Override
                    public void processNewLines(String[] lines) {
                        for (String line : lines) {
                            log.info("[scrcpy][{}]{}", mobileId, line);
                            if (!StringUtils.isEmpty(line)) {
                                if (line.contains("pid:")) { // [server] INFO: pid:11151
                                    pid = Integer.parseInt(line.substring(19));
                                    log.info("[scrcpy][{}]进程id: {}", mobileId, pid);
                                } else if (line.contains("wait for connection")) {
                                    countDownLatch.countDown();
                                }
                            }
                        }
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }
                }, 0, TimeUnit.SECONDS);
                log.info("[scrcpy][{}]已停止运行", mobileId);
                isRunning = false;
            } catch (Exception e) {
                throw new RuntimeException("启动scrcpy失败", e);
            }
        }).start();

        countDownLatch.await(30, TimeUnit.SECONDS);
        log.info("[scrcpy][{}]scrcpy启动完成", mobileId);
        isRunning = true;

        int localPort = PortProvider.getScrcpyAvailablePort();

        log.info("[scrcpy][{}]adb forward: {} -> remote scrcpy", mobileId, localPort);
        iDevice.createForward(localPort, "scrcpy", IDevice.DeviceUnixSocketNamespace.ABSTRACT);

        new Thread(() -> {
            Socket controlSocket = null;
            try (Socket screenSocket = new Socket("127.0.0.1", localPort);
                 InputStream screenStream = screenSocket.getInputStream()) {

                // Scrcpy.server - DesktopConnection.java
                // send one byte so the client may read() to detect a connection error
                // videoSocket.getOutputStream().write(0);
                if (screenStream.read() != 0) {
                    throw new RuntimeException(String.format("mobile: %s, scrcpy connection error", mobileId));
                }

                log.info("[scrcpy][{}]connect scrcpy success", mobileId);

                controlSocket = new Socket("127.0.0.1", localPort);
                controlOutputStream = controlSocket.getOutputStream();

                // Scrcpy.server - DesktopConnection.java
                // deviceName 64
                // width 2
                // height 2
                for (int i = 0; i < 64; i++) {
                    screenStream.read();
                }

                width = screenStream.read() << 8 | screenStream.read();
                heigth = screenStream.read() << 8 | screenStream.read();
                log.info("[scrcpy][{}]width: {} heigth: {}", deviceId, width, heigth);

                byte[] packet = new byte[1024 * 1024];
                int packetSize;

                while (isRunning) {
                    // Scrcpy.server - ScreenEncoder.java
                    // private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);
                    // headerBuffer.putLong(presentationTimeUs); 8字节
                    // headerBuffer.putInt(packetSize); 4字节
                    for (int i = 0; i < 8; i++) {
                        screenStream.read();
                    }

                    packetSize = IOUtil.readInt(screenStream);
                    if (packetSize > packet.length) {
                        packet = new byte[packetSize];
                    }

                    for (int i = 0; i < packetSize; i++) {
                        packet[i] = (byte) screenStream.read();
                    }

                    androidImgDataConsumer.consume(ByteBuffer.wrap(packet, 0, packetSize));
                }
            } catch (IndexOutOfBoundsException ign) {
            } catch (Exception e) {
                log.warn("[scrcpy][{}]处理scrcpy数据失败", mobileId, e);
            } finally {
                if (controlOutputStream != null) {
                    try {
                        controlOutputStream.close();
                    } catch (IOException e) {
                    }
                }
                if (controlSocket != null) {
                    try {
                        controlSocket.close();
                    } catch (IOException e) {
                    }
                }
            }
            log.info("[scrcpy][{}]已停止消费scrcpy图片数据", mobileId);

            // 移除adb forward
            try {
                log.info("[scrcpy][{}]移除adb forward: {} -> remote scrcpy", mobileId, localPort);
                iDevice.removeForward(localPort, "scrcpy", IDevice.DeviceUnixSocketNamespace.ABSTRACT);
            } catch (Exception e) {
                log.error("[scrcpy][{}]移除adb forward出错", mobileId, e);
            }
        }).start();
    }

    public synchronized void stop() {
        if (isRunning) {
            String cmd = "kill -9 " + pid;
            try {
                log.info("[scrcpy][{}]kill scrcpy: {}", mobileId, cmd);
                iDevice.executeShellCommand(cmd, new NullOutputReceiver());
            } catch (Exception e) {
                log.error("[scrcpy][{}]{}执行出错", mobileId, cmd, e);
            }
        }
    }

    private void pushScrcpyToDevice() throws Exception {
        log.info("[scrcpy][{}]push scrcpy to mobile, {} -> {}", mobileId, LOCAL_SCRCPY_PATH, REMOTE_SCRCPY_PATH);
        iDevice.pushFile(LOCAL_SCRCPY_PATH, REMOTE_SCRCPY_PATH);

        String chmodCmd = "chmod 777 " + REMOTE_SCRCPY_PATH;
        log.info("[scrcpy][{}]{} ", mobileId, chmodCmd);
        iDevice.executeShellCommand(chmodCmd, new NullOutputReceiver());
    }

    public void touchDown(int x, int y, int screenWidth, int screenHeight) {
        commitTouchEvent(ACTION_DOWN, x, y, screenWidth, screenHeight);
    }

    public void touchUp(int x, int y, int screenWidth, int screenHeight) {
        commitTouchEvent(ACTION_UP, x, y, screenWidth, screenHeight);
    }

    public void moveTo(int x, int y, int screenWidth, int screenHeight) {
        commitTouchEvent(ACTION_MOVE, x, y, screenWidth, screenHeight);
    }

    public void home() {
        commitKeyCode(KEYCODE_HOME);
    }

    public void back() {
        commitKeyCode(KEYCODE_BACK);
    }

    public void menu() {
        commitKeyCode(KEYCODE_MENU);
    }

    public void power() {
        commitKeyCode(KEYCODE_POWER);
    }

    // Scrcpy.server ControlMessage
    private static final int TYPE_INJECT_TOUCH_EVENT = 2;
    private static final int TYPE_INJECT_KEYCODE = 0;

    // android.view.MotionEvent
    private static final int ACTION_DOWN = 0;
    private static final int ACTION_UP = 1;
    private static final int ACTION_MOVE = 2;

    private ByteBuffer touchEventBuffer = ByteBuffer.allocate(28);

    // Scrcpy.server ControlMessageReader.parseInjectTouchEvent
    private void commitTouchEvent(int actionType, int x, int y, int screenWidth, int screenHeight) {
        // Scrcpy.server Device.computeVideoSize
        // 由于H264只接收8的倍数的宽高，所以scrcpy重新计算了video size
        // scrcpy输出的video size不能直接拿来用，否则会出现commitTouchEvent无效的问题
        if (screenHeight == maxSize) {
            screenWidth = heigth == maxSize ? width : heigth;
        } else if (screenWidth == maxSize) {
            screenHeight = width == maxSize ? heigth : width;
        }

        touchEventBuffer.rewind();

        touchEventBuffer.put((byte) TYPE_INJECT_TOUCH_EVENT);
        touchEventBuffer.put((byte) actionType);
        touchEventBuffer.putLong(-1L); // pointerId
        touchEventBuffer.putInt(x);
        touchEventBuffer.putInt(y);
        touchEventBuffer.putShort((short) screenWidth);
        touchEventBuffer.putShort((short) screenHeight);
        touchEventBuffer.putShort((short) 0xffff); // pressure
        touchEventBuffer.putInt(1); // buttons 鼠标左键: 1 << 0 | 右键: 1 << 1 | 中键: 1 << 2

        commit(touchEventBuffer.array());
    }

    // android.view.KeyEvent
    private static final int KEYCODE_HOME = 3;
    private static final int KEYCODE_BACK = 4;
    private static final int KEYCODE_MENU = 82;
    private static final int KEYCODE_POWER = 26;
    private static final int KEY_EVENT_ACTION_DOWN = 0;
    private static final int KEY_EVENT_ACTION_UP = 1;

    private ByteBuffer keycodeBuffer = ByteBuffer.allocate(20);

    // Scrcpy.server ControlMessageReader.parseInjectKeycode
    private void commitKeyCode(int keycode) {
        keycodeBuffer.rewind();

        keycodeBuffer.put((byte) TYPE_INJECT_KEYCODE);
        keycodeBuffer.put((byte) KEY_EVENT_ACTION_DOWN); // 按下
        keycodeBuffer.putInt(keycode); // keycode
        keycodeBuffer.putInt(0); // metaState

        keycodeBuffer.put((byte) TYPE_INJECT_KEYCODE);
        keycodeBuffer.put((byte) KEY_EVENT_ACTION_UP); // 抬起
        keycodeBuffer.putInt(keycode); // keycode
        keycodeBuffer.putInt(0); // metaState

        commit(keycodeBuffer.array());
    }

    private void commit(byte[] msg) {
        try {
            controlOutputStream.write(msg);
            controlOutputStream.flush();
        } catch (IOException e) {
            log.error("[scrcpy][{}]commit msg err", mobileId, e);
        }
    }
}
