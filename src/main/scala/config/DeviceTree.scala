package soc.config

import soc.isa.Extension

case class DeviceTreeOptions(
    compatible: String = "openion,ionsoc",
    model: String = "OpenIon IonSoC",
    timebaseFrequency: Int = 10000000,
    uartClockFrequency: Int = 50000000,
    uartBaud: Int = 115200
)

object DeviceTree {
    def linuxCapableDts(): String =
        dts(SoCProfiles.LinuxCapablePLIC, ISAProfiles.RV64IMACB)

    def dts(
        features: SoCFeatures,
        enabledExt: Set[Extension.Value] = ISAProfiles.RV64IMACB,
        options: DeviceTreeOptions = DeviceTreeOptions()
    ): String = {
        val isa = isaString(enabledExt)
        val mmu = if (features.mmu) """            mmu-type = "riscv,sv39";""" else ""
        val uart = if (features.uart) uartNode(features, options) else ""
        val clint = if (features.clint) clintNode() else ""
        val plic = if (features.interruptController == InterruptControllerKind.PLIC) plicNode() else ""

        s"""/dts-v1/;
           |
           |/ {
           |    #address-cells = <2>;
           |    #size-cells = <2>;
           |    compatible = "${options.compatible}";
           |    model = "${options.model}";
           |
           |    chosen {
           |        stdout-path = "serial0:${options.uartBaud}n8";
           |    };
           |
           |    cpus {
           |        #address-cells = <1>;
           |        #size-cells = <0>;
           |        timebase-frequency = <${options.timebaseFrequency}>;
           |
           |        cpu0: cpu@0 {
           |            device_type = "cpu";
           |            reg = <0>;
           |            status = "okay";
           |            compatible = "riscv";
           |            riscv,isa = "$isa";
           |$mmu
           |
           |            cpu0_intc: interrupt-controller {
           |                #interrupt-cells = <1>;
           |                interrupt-controller;
           |                compatible = "riscv,cpu-intc";
           |            };
           |        };
           |    };
           |
           |    memory@${hexAddr(features.sramBase)} {
           |        device_type = "memory";
           |        reg = <${regCells(features.sramBase, features.sramSizeBytes)}>;
           |    };
           |
           |    soc {
           |        #address-cells = <2>;
           |        #size-cells = <2>;
           |        compatible = "simple-bus";
           |        ranges;
           |$uart$clint$plic
           |    };
           |};
           |""".stripMargin
    }

    def isaString(enabledExt: Set[Extension.Value]): String = {
        val base = if (enabledExt.contains(Extension.RV64I)) "rv64i" else "rv32i"
        val singleLetterExt = Seq(
            Extension.RV64M -> "m",
            Extension.RV32M -> "m",
            Extension.RV64A -> "a",
            Extension.RV32A -> "a",
            Extension.RV64F -> "f",
            Extension.RV32F -> "f",
            Extension.RV64D -> "d",
            Extension.RV32D -> "d",
            Extension.C     -> "c"
        ).collect {
            case (ext, name) if enabledExt.contains(ext) => name
        }.distinct.mkString

        val multiLetterExt = Seq(
            Extension.Zicsr    -> "zicsr",
            Extension.Zifencei -> "zifencei",
            Extension.Zba      -> "zba",
            Extension.Zbb      -> "zbb",
            Extension.Zbs      -> "zbs"
        ).collect {
            case (ext, name) if enabledExt.contains(ext) => name
        }

        (base + singleLetterExt) + multiLetterExt.mkString("_", "_", "")
    }

    private def uartNode(features: SoCFeatures, options: DeviceTreeOptions): String = {
        val interruptProps =
            if (features.interruptController == InterruptControllerKind.PLIC) {
                s"""
                   |            interrupts = <${Config.UartPlicSource}>;
                   |            interrupt-parent = <&plic>;""".stripMargin
            } else {
                ""
            }

        s"""
           |
           |        serial0: uart@${hexAddr(Config.UartBase)} {
           |            compatible = "openion,ionsoc-uart";
           |            reg = <${regCells(Config.UartBase, Config.UartSize)}>;$interruptProps
           |            clock-frequency = <${options.uartClockFrequency}>;
           |            current-speed = <${options.uartBaud}>;
           |            status = "okay";
           |        };
           |""".stripMargin
    }

    private def clintNode(): String =
        s"""
           |
           |        clint: clint@${hexAddr(Config.ClintBase)} {
           |            compatible = "riscv,clint0";
           |            reg = <${regCells(Config.ClintBase, Config.ClintSize)}>;
           |            interrupts-extended = <&cpu0_intc 3>, <&cpu0_intc 7>;
           |        };
           |""".stripMargin

    private def plicNode(): String =
        s"""
           |
           |        plic: interrupt-controller@${hexAddr(Config.PlicBase)} {
           |            #address-cells = <0>;
           |            #interrupt-cells = <1>;
           |            compatible = "riscv,plic0";
           |            interrupt-controller;
           |            reg = <${regCells(Config.PlicBase, Config.PlicSize)}>;
           |            riscv,ndev = <${Config.plicSources}>;
           |            interrupts-extended = <&cpu0_intc 11>, <&cpu0_intc 9>;
           |        };
           |""".stripMargin

    private def regCells(base: BigInt, size: BigInt): String =
        s"${cell(base >> 32)} ${cell(base)} ${cell(size >> 32)} ${cell(size)}"

    private def cell(value: BigInt): String =
        "0x" + (value & BigInt("ffffffff", 16)).toString(16).reverse.padTo(8, '0').reverse

    private def hexAddr(value: BigInt): String =
        value.toString(16)
}
