package vexriscv.demo


import vexriscv.plugin._
import vexriscv._
import vexriscv.ip.{DataCacheConfig, InstructionCacheConfig}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.amba4.axi._
import spinal.lib.com.jtag.Jtag
import spinal.lib.com.jtag.sim.JtagTcp
// import spinal.lib.com.uart.sim.{UartDecoder, UartEncoder}
import spinal.lib.com.uart._
import spinal.lib.com.uart.{Apb3UartCtrl, Uart, UartCtrlGenerics, UartCtrlMemoryMappedConfig}
import spinal.lib.io.TriStateArray
import spinal.lib.misc.HexTools
import spinal.lib.soc.pinsec.{PinsecTimerCtrl, PinsecTimerCtrlExternal}
import spinal.lib.system.debugger.{JtagAxi4SharedDebugger, JtagBridge, SystemDebugger, SystemDebuggerConfig}

import scala.collection.mutable.ArrayBuffer

import spinal.lib.com.jtag.JtagTapInstructionCtrl


case class MuraxyConfig(axiFrequency : HertzNumber,
                       onChipRamSize : BigInt,
                       cpuPlugins : ArrayBuffer[Plugin[VexRiscv]],
                       uartCtrlConfig : UartCtrlMemoryMappedConfig)

object MuraxyConfig{

  def default = {
    val config = MuraxyConfig(
      axiFrequency = 100 MHz,
      onChipRamSize  = 32 kB,
      uartCtrlConfig = UartCtrlMemoryMappedConfig(
        uartCtrlConfig = UartCtrlGenerics(
            dataWidthMax      = 8,
            clockDividerWidth = 20,
            preSamplingSize   = 1,
            samplingSize      = 3,
            postSamplingSize  = 1
        ),
        initConfig = UartCtrlInitConfig(
            baudrate = 115200,
            dataLength = 7,  //7 => 8 bits
            parity = UartParityType.NONE,
            stop = UartStopType.ONE
        ),
        busCanWriteClockDividerConfig = false,
        busCanWriteFrameConfig = false,
        txFifoDepth = 16,
        rxFifoDepth = 16
      ),
      cpuPlugins = ArrayBuffer(
        new PcManagerSimplePlugin(0x80000000l, false),
        new IBusCachedPlugin(
          resetVector = 0x80000000l,
          prediction = DYNAMIC_TARGET,
          historyRamSizeLog2 = 8,
          config = InstructionCacheConfig(
            cacheSize = 4096*2,
            bytePerLine =32,
            wayCount = 1,
            addressWidth = 32,
            cpuDataWidth = 32,
            memDataWidth = 32,
            catchIllegalAccess = true,
            catchAccessFault = true,
            asyncTagMemory = false,
            twoCycleRam = false,
            twoCycleCache = true
          )
        ),

        new DBusCachedPlugin(
          config = new DataCacheConfig(
            cacheSize         = 4096*2,
            bytePerLine       = 32,
            wayCount          = 4,
            addressWidth      = 32,
            cpuDataWidth      = 32,
            memDataWidth      = 32,
            catchAccessError  = true,
            catchIllegal      = true,
            catchUnaligned    = true
          ),
          memoryTranslatorPortConfig = null
          //            memoryTranslatorPortConfig = MemoryTranslatorPortConfig(
          //              portTlbSize = 6
          //            )
        ),
        new StaticMemoryTranslatorPlugin(
          ioRange      = _(31 downto 28) === 0xF
        ),
        new DecoderSimplePlugin(
          catchIllegalInstruction = true
        ),
        new RegFilePlugin(
          regFileReadyKind = plugin.SYNC,
          zeroBoot = false
        ),
        new IntAluPlugin,
        new SrcPlugin(
          separatedAddSub = false,
          executeInsertion = true
        ),
        new FullBarrelShifterPlugin(earlyInjection = true),
        new MulPlugin,
        new DivPlugin,
        new HazardSimplePlugin(
          bypassExecute           = true,
          bypassMemory            = true,
          bypassWriteBack         = true,
          bypassWriteBackBuffer   = true,
          pessimisticUseSrc       = false,
          pessimisticWriteRegFile = false,
          pessimisticAddressMatch = false
        ),
        new BranchPlugin(
          earlyBranch = false,
          catchAddressMisaligned = true
        ),
        new CsrPlugin(CsrPluginConfig.small),
//         new CsrPlugin(
//           config = CsrPluginConfig(
//             catchIllegalAccess = false,
//             mvendorid      = null,
//             marchid        = null,
//             mimpid         = null,
//             mhartid        = null,
//             misaExtensionsInit = 66,
//             misaAccess     = CsrAccess.NONE,
//             mtvecAccess    = CsrAccess.NONE,
//             mtvecInit      = 0x80000020l,
//             mepcAccess     = CsrAccess.READ_WRITE,
//             mscratchGen    = false,
//             mcauseAccess   = CsrAccess.READ_ONLY,
//             mbadaddrAccess = CsrAccess.READ_ONLY,
//             mcycleAccess   = CsrAccess.NONE,
//             minstretAccess = CsrAccess.NONE,
//             ecallGen       = false,
//             wfiGenAsWait         = false,
//             ucycleAccess   = CsrAccess.NONE,
//             uinstretAccess = CsrAccess.NONE
//           )
//         ),
        new YamlPlugin("cpu0.yaml")
      )
    )
    config
  }
}



class Muraxy(val config: MuraxyConfig) extends Component{

  //Legacy constructor
  def this(axiFrequency: HertzNumber) {
    this(MuraxyConfig.default.copy(axiFrequency = axiFrequency))
  }

  import config._
  val debug = true
  val interruptCount = 4

  val io = new Bundle{
    //Clocks / reset
    val asyncReset = in Bool()
    val mainClk     = in Bool()

    //Main components IO
//     val jtag       = slave(Jtag())

    //Peripherals IO
    val gpioA         = master(TriStateArray(32 bits))
    val uart          = master(Uart())

//     val coreInterrupt = in Bool()
  }

  val resetCtrlClockDomain = ClockDomain(
    clock = io.mainClk,
    config = ClockDomainConfig(
      resetKind = BOOT
    )
  )

  val resetCtrl = new ClockingArea(resetCtrlClockDomain) {
    val systemResetUnbuffered  = False
    //    val coreResetUnbuffered = False

    //Implement an counter to keep the reset axiResetOrder high 64 cycles
    // Also this counter will automaticly do a reset when the system boot.
    val systemResetCounter = Reg(UInt(6 bits)) init(0)
    when(systemResetCounter =/= U(systemResetCounter.range -> true)){
      systemResetCounter := systemResetCounter + 1
      systemResetUnbuffered := True
    }
    when(BufferCC(io.asyncReset)){
      systemResetCounter := 0
    }

    //Create all reset used later in the design
    val systemReset  = RegNext(systemResetUnbuffered)
    val axiReset     = RegNext(systemResetUnbuffered)
//     val vgaReset     = BufferCC(axiReset)
  }

  val axiClockDomain = ClockDomain(
    clock = io.mainClk,
    reset = resetCtrl.axiReset,
    frequency = FixedFrequency(axiFrequency) //The frequency information is used by the SDRAM controller
  )

  val debugClockDomain = ClockDomain(
    clock = io.mainClk,
    reset = resetCtrl.systemReset,
    frequency = FixedFrequency(axiFrequency)
  )

  val axi = new ClockingArea(axiClockDomain) {
    val ram = Axi4SharedOnChipRam(
      dataWidth = 32,
      byteCount = onChipRamSize,
      idWidth = 4
    )

    val apbBridge = Axi4SharedToApb3Bridge(
      addressWidth = 20,
      dataWidth    = 32,
      idWidth      = 4
    )

    val gpioACtrl = Apb3Gpio(
      gpioWidth = 32,
      withReadSync = true
    )

    val uartCtrl = Apb3UartCtrl(uartCtrlConfig)
    uartCtrl.io.apb.addAttribute(Verilator.public)

    val timer = new MuraxApb3Timer()

    val core = new Area{
      val config = VexRiscvConfig(
        plugins = cpuPlugins += new DebugPlugin(debugClockDomain)
      )

      val cpu = new VexRiscv(config)
      var iBus : Axi4ReadOnly = null
      var dBus : Axi4Shared = null
      for(plugin <- config.plugins) plugin match{
        case plugin : IBusSimplePlugin => iBus = plugin.iBus.toAxi4ReadOnly()
        case plugin : IBusCachedPlugin => iBus = plugin.iBus.toAxi4ReadOnly()
        case plugin : DBusSimplePlugin => dBus = plugin.dBus.toAxi4Shared()
        case plugin : DBusCachedPlugin => dBus = plugin.dBus.toAxi4Shared(true)
        case plugin : CsrPlugin        => {
//           plugin.externalInterrupt := BufferCC(io.coreInterrupt)
          plugin.externalInterrupt := False
          plugin.timerInterrupt := timer.io.interrupt
        }
        case plugin : DebugPlugin      => debugClockDomain{
          resetCtrl.axiReset setWhen(RegNext(plugin.io.resetOut))
//           io.jtag <> plugin.io.bus.fromJtag()
          val jtagCtrl = JtagTapInstructionCtrl()
          val tap = jtagCtrl.fromXilinxBscane2(userId = 2)
          jtagCtrl <> plugin.io.bus.fromJtagInstructionCtrl(ClockDomain(tap.TCK))
        }
        case _ =>
      }
    }


    val axiCrossbar = Axi4CrossbarFactory()

    axiCrossbar.addSlaves(
      ram.io.axi       -> (0x80000000L,   onChipRamSize),
      apbBridge.io.axi -> (0xF0000000L,   1 MB)
    )

    axiCrossbar.addConnections(
      core.iBus       -> List(ram.io.axi),
      core.dBus       -> List(ram.io.axi, apbBridge.io.axi)
    )


    axiCrossbar.addPipelining(apbBridge.io.axi)((crossbar,bridge) => {
      crossbar.sharedCmd.halfPipe() >> bridge.sharedCmd
      crossbar.writeData.halfPipe() >> bridge.writeData
      crossbar.writeRsp             << bridge.writeRsp
      crossbar.readRsp              << bridge.readRsp
    })


    axiCrossbar.addPipelining(ram.io.axi)((crossbar,ctrl) => {
      crossbar.sharedCmd.halfPipe()  >>  ctrl.sharedCmd
      crossbar.writeData            >/-> ctrl.writeData
      crossbar.writeRsp              <<  ctrl.writeRsp
      crossbar.readRsp               <<  ctrl.readRsp
    })

    axiCrossbar.addPipelining(core.dBus)((cpu,crossbar) => {
      cpu.sharedCmd             >>  crossbar.sharedCmd
      cpu.writeData             >>  crossbar.writeData
      cpu.writeRsp              <<  crossbar.writeRsp
      cpu.readRsp               <-< crossbar.readRsp //Data cache directly use read responses without buffering, so pipeline it for FMax
    })

    axiCrossbar.build()

    val apbDecoder = Apb3Decoder(
      master = apbBridge.io.apb,
      slaves = List(
        gpioACtrl.io.apb -> (0x00000, 4 kB),
        uartCtrl.io.apb  -> (0x10000, 4 kB),
        timer.io.apb -> (0x20000, 4 kB)
      )
    )
  }

  io.gpioA          <> axi.gpioACtrl.io.gpio
  io.uart           <> axi.uartCtrl.io.uart
}

object Muraxy{
  def main(args: Array[String]) {
    val config = SpinalConfig()
    config.generateVerilog({
      val toplevel = new Muraxy(MuraxyConfig.default)

      toplevel
    })
  }
}

object MuraxyWithRamInit{
  def main(args: Array[String]) {
    val config = SpinalConfig()
    config.generateVhdl({
      val toplevel = new Muraxy(MuraxyConfig.default)
      HexTools.initRam(toplevel.axi.ram.ram, "../coremark/build/coremark.hex", 0x80000000l)
      toplevel
    })
  }
}
