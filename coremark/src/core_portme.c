/*
Copyright 2018 Embedded Microprocessor Benchmark Consortium (EEMBC)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Original Author: Shay Gal-on
*/

/* Modified for the VexRiscV processor (from NEORV32 Version by Stephan Nolting: https://github.com/stnolting/neorv32/tree/master/sw/example/coremark)*/

#include "coremark.h"
#include "core_portme.h"

#if VALIDATION_RUN
volatile ee_s32 seed1_volatile = 0x3415;
volatile ee_s32 seed2_volatile = 0x3415;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PERFORMANCE_RUN
volatile ee_s32 seed1_volatile = 0x0;
volatile ee_s32 seed2_volatile = 0x0;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PROFILE_RUN
volatile ee_s32 seed1_volatile = 0x8;
volatile ee_s32 seed2_volatile = 0x8;
volatile ee_s32 seed3_volatile = 0x8;
#endif
volatile ee_s32 seed4_volatile = ITERATIONS;
volatile ee_s32 seed5_volatile = 0;
/* Porting : Timing functions
        How to capture time and convert to seconds must be ported to whatever is
   supported by the platform. e.g. Read value from on board RTC, read value from
   cpu clock cycles performance counter etc. Sample implementation for standard
   time.h and windows.h definitions included.
*/
CORETIMETYPE
barebones_clock()
{
/*
#error \
    "You must implement a method to measure time in barebones_clock()! This function should return current time.\n"
*/
  return 0;
}
/* Define : TIMER_RES_DIVIDER
        Divider to trade off timer resolution and total time that can be
   measured.

        Use lower values to increase resolution, but make sure that overflow
   does not occur. If there are issues with the return value overflowing,
   increase this value.
        */
#define GETMYTIME(_t)              (*_t = (CORETIMETYPE)neorv32_cpu_get_cycle())
#define MYTIMEDIFF(fin, ini)       ((fin) - (ini))
#define TIMER_RES_DIVIDER          1
#define SAMPLE_TIME_IMPLEMENTATION 1
#define EE_TICKS_PER_SEC           (CLOCKS_PER_SEC / TIMER_RES_DIVIDER)

/** Define Host specific (POSIX), or target specific global time variables. */
static CORETIMETYPE start_time_val, stop_time_val;
#define TIMER_A ((Timer_Reg*)0xF0020040)

/* Function : start_time
        This function will be called right before starting the timed portion of
   the benchmark.

        Implementation may be capturing a system timer (as implemented in the
   example code) or zeroing some system parameters - e.g. setting the cpu clocks
   cycles to 0.
*/
void
start_time(void)
{
  // VexRiscV specific
  start_time_val = 0;
  prescaler_init(TIMER_PRESCALER);
  timer_init(TIMER_A);

  TIMER_PRESCALER->LIMIT = 1000000;

  TIMER_A->LIMIT = 65535;
  TIMER_A->CLEARS_TICKS = 0x00010002;

}
/* Function : stop_time
        This function will be called right after ending the timed portion of the
   benchmark.

        Implementation may be capturing a system timer (as implemented in the
   example code) or other system parameters - e.g. reading the current value of
   cpu cycles counter.
*/
void
stop_time(void)
{
  // VexRiscV specific
  stop_time_val = TIMER_A->VALUE;
}
/* Function : get_time
        Return an abstract "ticks" number that signifies time on the system.

        Actual value returned may be cpu cycles, milliseconds or any other
   value, as long as it can be converted to seconds by <time_in_secs>. This
   methodology is taken to accomodate any hardware or simulated platform. The
   sample implementation returns millisecs by default, and the resolution is
   controlled by <TIMER_RES_DIVIDER>
*/
CORE_TICKS
get_time(void)
{
    CORE_TICKS elapsed
        = (CORE_TICKS)(MYTIMEDIFF(stop_time_val, start_time_val));
    return elapsed;
}
/* Function : time_in_secs
        Convert the value returned by get_time to seconds.

        The <secs_ret> type is used to accomodate systems with no support for
   floating point. Default implementation implemented by the EE_TICKS_PER_SEC
   macro above.
*/
secs_ret
time_in_secs(CORE_TICKS ticks)
{
    secs_ret retval = (secs_ret)(((CORE_TICKS)ticks) / ((CORE_TICKS)SYSINFO_CLK));
    return retval;
}

ee_u32 default_num_contexts = 1;

/* Number of available hardware performance monitors */
uint32_t num_hpm_cnts_global = 0;


/* Function : portable_init
        Target specific initialization code
        Test for some common mistakes.
*/
#ifndef RUN_COREMARK
void
__attribute__((__noreturn__))
portable_init(core_portable *p, int *argc, char *argv[])
#else
void
portable_init(core_portable *p, int *argc, char *argv[])
#endif
{


// Disable coremark compilation by default
#ifndef RUN_COREMARK
  #warning COREMARK HAS NOT BEEN COMPILED! Use >>make USER_FLAGS+=-DRUN_COREMARK clean_all exe<< to compile it.

  // inform the user if you are actually executing this
  ee_printf("ERROR! CoreMark has not been compiled. Use >>make USER_FLAGS+=-DRUN_COREMARK clean_all exe<< to compile it.\n");

  while(1);
#endif

  ee_printf("vexRiscv: Processor running at %u Hz\n", (uint32_t)SYSINFO_CLK);
  if (ITERATIONS == 0) {
      ee_printf("vexRiscv: Executing coremark (Number of iterations dynamically calculated). This may take some time...\n\n");
  } else {
    ee_printf("vexRiscv: Executing coremark (%u iterations). This may take some time...\n\n", (uint32_t)ITERATIONS);
  }


/*
#error \
    "Call board initialization routines in portable init (if needed), in particular initialize UART!\n"
*/
    if (sizeof(ee_ptr_int) != sizeof(ee_u8 *))
    {
        ee_printf(
            "ERROR! Please define ee_ptr_int to a type that holds a "
            "pointer!\n");
    }
    if (sizeof(ee_u32) != 4)
    {
        ee_printf("ERROR! Please define ee_u32 to a 32b unsigned type!\n");
    }
    p->portable_id = 1;

#ifndef RUN_COREMARK
  while(1);
#endif
}


/* Function : portable_fini
        Target specific final code
*/
void
portable_fini(core_portable *p)
{
    p->portable_id = 0;

  // show executed instructions, required cycles and resulting average CPI
  union {
    uint64_t uint64;
    uint32_t  uint32[sizeof(uint64_t)/2];
  } exe_instructions, exe_time;

  exe_time.uint64 = (uint64_t)get_time();

  ee_printf("\nVexRiscV: All reported numbers only show the integer part.\n\n");

}

void irqCallback(){
}
