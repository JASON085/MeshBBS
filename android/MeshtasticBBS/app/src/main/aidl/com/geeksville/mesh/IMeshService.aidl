// Minimal AIDL interface — method ORDER must match the real IMeshService exactly.
// Transaction codes are FIRST_CALL_TRANSACTION + (0-indexed method position).
// Sourced by empirical probing against Meshtastic 2.7.13 (com.geeksville.mesh).
//
// Confirmed:
//   tx+0  getMyId()         → returns our node ID string
//   tx+4  subscribeReceiver → CONFIRMED WORKS
//   tx+7  send()            → returns positive packet ID on success
//
// tx+1, tx+5, tx+6 are unknown; placeholder stubs keep the numbering aligned.
// tx+2  getNodes()          → SecurityException in 2.7.x (third-party restriction)
// tx+3  isConnected()       → returns false when radio not ready
package com.geeksville.mesh;
import com.geeksville.mesh.DataPacket;
import com.geeksville.mesh.NodeInfo;

interface IMeshService {
    /** tx+0 */
    String getMyId();

    /** tx+1 — placeholder; do not call */
    String placeholder1();

    /** tx+2 — SecurityException in Meshtastic 2.7.x for third-party apps */
    List<NodeInfo> getNodes();

    /** tx+3 */
    boolean isConnected();

    /** tx+4: subscribeReceiver — CONFIRMED WORKS */
    void subscribeReceiver(String packageName, String receiverName);

    /** tx+5: send — returns consecutive packet ID on each call (confirmed empirically) */
    int send(in DataPacket packet);

    /** tx+6 — placeholder; do not call */
    int placeholder6();

    /** tx+7 — returns fixed int 892 regardless of input; likely getMyNodeNum() */
    int placeholder7();
}
