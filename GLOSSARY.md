# MeshLink

MeshLink lets nearby phones form a peer-to-peer mesh over Bluetooth Low Energy and exchange messages directly or through other peers.

## Mesh

**Node**:
One running instance of the library on one device, identified by its installation identity.
_Avoid_: Device, phone, peer (when meaning the local instance)

**Peer**:
Another node, as seen from the local node.
_Avoid_: Neighbor (unless directly linked), remote, contact

**Installation identity**:
The long-lived identity of one app installation; applications map installation identities to their own users.
_Avoid_: User ID, account, device ID

**Preset**:
A named bundle of enabled features and settings that matches a reference scenario.
_Avoid_: Profile (reserved for security profile), mode, template

## Messaging

**Message**:
A bounded unit of application data addressed to one destination and carried whole or in fragments.
_Avoid_: Packet, payload, file, stream

**Delivery receipt**:
An optional confirmation that the destination node received and authenticated a specific message.
_Avoid_: Ack, read receipt

**Read receipt**:
An optional confirmation, triggered by the receiving application, that a delivered message was read; the library only carries it.
_Avoid_: Delivery receipt, seen

**Mesh broadcast**:
A message addressed to every node within a bounded number of hops rather than to one destination.
_Avoid_: Flood (that names a forwarding technique), multicast, group message

**Store-and-forward**:
Holding a message for a destination that is currently unreachable and forwarding it when a route appears, within a bounded lifetime and storage quota.
_Avoid_: Caching, offline queue

## Security

**Security profile**:
The single protection level (none, classical, or post-quantum) shared by every node of a mesh; nodes with a different profile do not join rather than downgrade.
_Avoid_: Security level, crypto mode, tier
