import com.odin2portal.control.hardware.KernelPath
import com.odin2portal.control.hardware.ShellProtocol
import com.odin2portal.control.domain.Parsing
fun main() {
    require(KernelPath.parse("/sys/class/kgsl/kgsl-3d0/gpubusy") != null)
    require(KernelPath.parse("/sys/../data") == null)
    require(KernelPath.parse("/sys/a;reboot") == null)
    require(!ShellProtocol.parse("").verified)
    require(ShellProtocol.parse("2400000\n__O2P_CC_RC_V1__=0\n").value == "2400000")
    require(!ShellProtocol.parse("failed\n__O2P_CC_RC_V1__=2\n").verified)
    require(Parsing.cpuList("0-2, 3-6 7") == (0..7).toList())
    require(Parsing.memoryKb("MemAvailable: 900 kB", "MemAvailable") == 900L)
    require(Parsing.temperatureC("62400") == 62.4)
    println("PASS: 9 JVM smoke checks")
}
