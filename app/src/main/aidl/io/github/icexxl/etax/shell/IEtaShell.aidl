// 派生自 Mika (Apache-2.0) 的 IMikaShell，仅保留 EtaX 免 Root 虚拟屏所需的方法。
package io.github.icexxl.etax.shell;

import android.os.Bundle;
import android.view.Surface;

// 运行在 shell 身份(uid 2000)进程里的常驻服务:由 EtaX 经本机无线调试拉起。
interface IEtaShell {
    // 建一块虚拟屏，画面进调用方给来的 Surface；返回 displayId，失败返回 -1
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay(int displayId) = 2;
    // 0=已启动 1=用户正在主屏使用该 App(不启动) 2=没有可启动的入口 3=出错
    int startApp(int displayId, String packageName) = 3;
    // 这块屏最上面任务的 id；没有任务返回 -1
    int topTaskId(int displayId) = 4;
    // 把任务挪到指定屏；用于虚拟屏 ↔ 主屏之间来回
    boolean moveTask(int taskId, int displayId) = 5;
    boolean injectKey(int displayId, int keyCode) = 6;
    boolean injectTap(int displayId, int x, int y) = 7;
    // 一根手指的一笔触摸：times 相对按下时刻(ms)，第一个点按下、最后一个点抬起
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times) = 8;
    // 任务所在屏；任务不存在返回 -1
    int displayOfTask(int taskId) = 9;
    // 任务最上面页面所属包；任务不存在返回 null
    String packageOfTask(int taskId) = 10;
    // 静音/恢复该包的 PLAY_AUDIO
    boolean setAudioMuted(String packageName, boolean muted) = 11;
    // 任务结束：按规则处理屏上的 App 再释放这块屏。返回 {kept, closed, note}
    Bundle finishDisplay(int displayId, in String[] keep, in String[] close) = 12;
    // 助手进程的 [uid, pid]，交接时校验
    int[] identity() = 13;
    // 撤掉本次绑定留下的改动(还原静音、释放所有虚拟屏)，进程不退出
    void releaseAll() = 14;
    // 该包在主屏上有看得见的任务时返回 true；看不清时也返回 true(宁可不动)
    boolean visibleOnMainScreen(String packageName) = 15;
    // 在已建好的这块屏上以 shell 身份跑命令，返回 {exit_code, output}
    Bundle runSystemCommand(int displayId, String command) = 16;
}
