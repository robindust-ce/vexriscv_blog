# vexriscv_blog

This is the accompanying repository to [this blog](https://www.hackster.io/robindust/this-will-be-your-new-favourite-cpu-for-fpgas-65800e).
The repository contains the code used to evaluate the Open-Source [VexRiscv](https://github.com/SpinalHDL/VexRiscv) Softcore. This includes a CoreMark port, two VexRiscv SoC variants and a tcl script that creates a vivado project for the Digilent Nexys-A7 board.

- Murax.vhd features a resource-efficient Vexriscv (rv32i) configuration.
- Muraxy.vhd features a high-performance Vexriscv (rv32im), which is a cached version of the Murax SoC and includes other performance features such as branch prediction and the multiplier plugin.
Both SoC variants include an UART interface and GPIOs. 
