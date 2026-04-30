package ch.trancee.meshlink.tui

import ch.trancee.meshlink.tui.mesh.VirtualMeshNetwork
import kotlinx.coroutines.*
import java.io.OutputStream
import java.io.PrintStream

/**
 * MeshLink TUI — Interactive terminal UI for testing MeshLink with virtual transport.
 *
 * Usage:
 *   ./gradlew :meshlink-tui:fatJar
 *   java -jar meshlink-tui/build/libs/meshlink-tui-all.jar
 *
 * Controls:
 *   [1-6]   Switch tabs (Log, Peers, Routing, Health, Network, Send)
 *   [←/→]   Switch active node
 *   [↑/↓]   Scroll log / move cursor (Network tab)
 *   [:]     Enter command mode
 *   [q]     Quit
 *
 * Network tab shortcuts:
 *   [a]     Add new node
 *   [d]     Remove selected node
 *   [p]     Pause/resume selected node
 *   [l]     Link selected → next node
 *   [u]     Unlink selected → next node
 *   [x]     Simulate disconnect for selected node
 *   [r]     Reconnect selected node
 *
 * Send tab shortcuts:
 *   [i]     Enter input mode
 *   [b]     Broadcast message
 *   [Tab]   Switch send target
 *
 * Commands (type ':' then command, then Enter):
 *   :star             Reconfigure as star topology
 *   :ring             Reconfigure as ring topology
 *   :line             Reconfigure as line topology
 *   :mesh             Reconfigure as full mesh
 *   :partition        Split network into two halves
 *   :heal             Reconnect partitioned halves
 *   :add <name>       Add a named node
 *   :flood <f> <t> <n>  Send N messages from node f to node t
 *   :link <a> <b>     Link nodes at indices a and b
 *   :unlink <a> <b>   Unlink nodes at indices a and b
 *   :help             Show available commands
 */
fun main() {
    runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val network = VirtualMeshNetwork(scope)

        System.err.println("MeshLink TUI — Starting virtual mesh...")
        network.setup()
        System.err.println("Network ready. Launching TUI...")

        // Redirect stdout/stderr to suppress internal MeshLink Logger.d prints
        val nullStream = PrintStream(OutputStream.nullOutputStream())
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(nullStream)
        System.setErr(nullStream)

        val tui = MeshLinkTui(network, scope)
        try {
            tui.run()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            System.err.println("TUI exited. Cleaning up...")
            scope.cancel()
            System.err.println("Exiting...")
            Runtime.getRuntime().halt(0)
        }
    }
}
