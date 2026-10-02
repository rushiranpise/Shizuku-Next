package moe.shizuku.manager.start

/**
 * Which transport a start will use, as a decision that can be reasoned about without a
 * device.
 *
 * Two of them carry a start: the wireless (TLS) port, which is found over mDNS and so
 * needs a network interface, and the classic ADB port, which is a number that is already
 * known and needs nothing at all. TCP mode is what keeps the classic port open, so once
 * it is on and the port is there a start does not need Wi-Fi which is the point of the
 * mode, and what makes a restart after a reboot work on a device with no network.
 */
object StartTransport {

    /**
     * Whether a start has to wait for a network before it can reach the TLS port.
     *
     * [forceWireless] is the experimental setting that asks for wireless debugging
     * without a network at all: waiting for one would gate the exact start it exists to
     * make possible, which is why it overrides everything else here.
     */
    fun wifiRequired(tcpPort: Int, tcpMode: Boolean, forceWireless: Boolean = false): Boolean =
        !forceWireless && (tcpPort <= 0 || !tcpMode)

    /**
     * The port to use when discovery over mDNS has found nothing, or null when there is
     * nothing to fall back to.
     */
    fun classicPortFallback(tcpPort: Int): Int? = tcpPort.takeIf { it > 0 }
}
