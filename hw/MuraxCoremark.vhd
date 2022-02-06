library IEEE;
use IEEE.STD_LOGIC_1164.ALL;
-- library UNISIM;
-- use UNISIM.VCOMPONENTS.ALL;

entity MuraxCoremark is
  port (
    CLK100MHZ : in STD_LOGIC;
    LED : out STD_LOGIC_VECTOR ( 15 downto 0 );
    io_uart_rxd_0 : in STD_LOGIC;
    io_uart_txd_0 : out STD_LOGIC;
    reset : in STD_LOGIC
  );
end MuraxCoremark;

architecture STRUCTURE of MuraxCoremark is

 component Muraxy
        port (
            io_asyncReset           : in std_logic;
            io_mainClk              : in std_logic;
            io_gpioA_read           : in std_logic_vector(31 downto 0);
            io_gpioA_write          : out std_logic_vector(31 downto 0);
            io_gpioA_writeEnable    : out std_logic_vector(31 downto 0);
            io_uart_txd             : out std_logic;
            io_uart_rxd             : in std_logic
        );
    end component;
    
    signal inv_reset : std_logic;
    signal gpio_o : std_logic_vector(31 downto 0);
  
begin

    inv_reset <= not reset;

i_murax : Muraxy
     port map (
        io_asyncReset           => inv_reset,
        io_mainClk              => CLK100MHZ,
        io_gpioA_read           => (others => '0'),           
        io_gpioA_write          => gpio_o,
        io_gpioA_writeEnable    => open,
        io_uart_txd             => io_uart_txd_0,
        io_uart_rxd             => io_uart_rxd_0
    );
    
    LED <= gpio_o(15 downto 0);
    
end STRUCTURE;
