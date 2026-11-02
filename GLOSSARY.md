# MeshLink

MeshLink lets nearby phones form a peer-to-peer mesh over Bluetooth Low Energy and exchange messages directly or through other peers.

## Mesh

**Mesh**:
The set of nodes that share one mesh identifier and one security profile and can reach each other directly or through peers.
_Avoid_: Network (too broad), group, swarm

**Node**:
One running instance of the library on one device, identified by its installation identity.
_Avoid_: Device, phone, peer (when meaning the local instance)

**Peer**:
Another node, as seen from the local node.
_Avoid_: Neighbor (unless directly linked), remote, contact

**Link**:
A direct Bluetooth connection between two nodes, kept as exactly one per pair even when both connect at once; it carries frames over one bearer at a time.
_Avoid_: Connection (for the platform object), channel, session

**Bearer**:
The Bluetooth transport a link uses to carry frames: GATT, or an L2CAP connection-oriented channel that a link upgrades to and falls back from.
_Avoid_: Transport (too broad), PHY (that is the radio mode), protocol

**Installation identity**:
The long-lived identity of one app installation; applications map installation identities to their own users.
_Avoid_: User ID, account, device ID

**Preset**:
A named bundle of enabled features and settings, either built in for a reference scenario or defined by the application on top of a built-in one; local to a node, so nodes with different presets can share a mesh.
_Avoid_: Profile (reserved for security profile), mode, template

**Proximity**:
A coarse, locally measured estimate of how near a directly linked peer is; never a distance in meters and never sent to other nodes.
_Avoid_: Distance, range, location

**Local density**:
The number of peers within radio range of one node, as that node estimates it, grouped into a provisional class (low, medium or high). It differs between nodes and changes over time; each node adapts its own behavior to it.
_Avoid_: Density (unqualified), mesh size, crowd size

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

**Message lifetime**:
The time, set by the sender, after which a message is invalid everywhere and no node forwards or holds it.
_Avoid_: TTL, expiry (ambiguous), time-to-live

**Hop limit**:
The largest number of relays a message may cross before it is no longer forwarded.
_Avoid_: TTL, hop count (that is the number already crossed), range

## Security

**Security profile**:
The single protection level (unsecured, classical, or post-quantum) shared by every node of a mesh; nodes with a different profile do not join rather than downgrade.
_Avoid_: Security level, crypto mode, tier

**Mesh key**:
A secret shared out of band by the application that a node must hold to join a gated mesh; it grants membership, not individual identity.
_Avoid_: Network key, password, group key

**Open mesh**:
A mesh that any node with its mesh identifier and security profile may join.
_Avoid_: Public mesh, unsecured mesh (an open mesh may use a secured profile)

**Gated mesh**:
A mesh that only nodes holding its mesh key may join; available only with a secured security profile.
_Avoid_: Private mesh, closed group

**Advertisement identifier**:
The rotating value a node puts in its advertisement; outsiders cannot link it across rotations, so a rotated identifier looks like a new device until a handshake or a resolving key says otherwise.
_Avoid_: Beacon ID, MAC, device address

**Resolving key**:
A per-node secret, rotated daily, that a node in a gated mesh gives only to peers inside an authenticated link handshake, so those peers can recognize its advertisement identifiers across rotations.
_Avoid_: IRK (the BLE bonding key), mesh key
