package org.meshtastic.core.service;

import org.meshtastic.core.model.DataPacket;
import org.meshtastic.core.model.MeshUser;

interface IMeshService {
    /** tx+0: tell Meshtastic where to send manifest-declared received-packet broadcasts. */
    void subscribeReceiver(String packageName, String receiverName);

    /** tx+1: unused by MeshBBS, kept so later transaction codes match Meshtastic 2.7.x. */
    void setOwner(in MeshUser user);

    /** tx+2: unused placeholder. */
    void setRemoteOwner(in int requestId, in int destNum, in byte[] payload);

    /** tx+3: unused placeholder. */
    void getRemoteOwner(in int requestId, in int destNum);

    /** tx+4 */
    String getMyId();

    /** tx+5 */
    int getPacketId();

    /** tx+6: official Meshtastic text/data send API. */
    void send(inout DataPacket packet);

    /** tx+7 */
    List getNodes();
}
