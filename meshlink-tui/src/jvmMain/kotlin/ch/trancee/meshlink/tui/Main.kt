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
 *   [1-5]   Switch tabs (Log, Peers, Routing, Health, Send)
 *   [←/→]   Switch active node
 *   [↑/↓]   Scroll log
 *   [i]     Enter input mode (Send tab)
 *   [b]     Broadcast message (Send tab)
 *   [Tab]   Switch send target (Send tab)
 *   [q]     Quit
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
