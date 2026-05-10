#include <cstdio>
#include <cstring>
#include <string>
#include <sys/stat.h>
#include <filesystem>
#include <elf.h>
#include <cstdlib>
#include <cstdint>
#include <cerrno>
#include <cinttypes>
#include <fcntl.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <vector>
#include <algorithm>
#include <verilated.h>
#if VM_TRACE
#include <verilated_vcd_c.h>
#endif
#include "VSoc.h"
#include "VSoc___024root.h"
#include "VSoc_L1Cache.h"

#define RED "\033[31m"
#define GREEN "\033[32m"
#define YELLOW "\033[33m"
#define CEND "\033[0m"

#define BROM_BASE 0x80000000
#define ROM_SIZE 0x10000 // 64KB = 16384 words * 4 bytes
#define SRAM_BASE 0x10000000
#define FIRMWARE_SRAM_BASE 0x40000000
#define DEFAULT_SRAM_SIZE 0x10000
#define DEFAULT_FIRMWARE_SRAM_SIZE 0x01000000

#define MAX_SIM_CYCLES 10000
static const char *kPayloadElfPath = "simulator/build/payload/payload.elf";
static const char *kWavePath = "simulator/build/wave.vcd";
vluint64_t sim_time = 0;

struct SimOptions;

void load_elf(VSoc *dut, const char *path);
void load_elf_to_regions(VSoc *dut, const char *path, uint64_t sram_base, size_t sram_size);
void load_blob_to_sram(VSoc *dut, const char *path, uint64_t paddr, uint64_t sram_base, size_t sram_size);
bool run_one_test(const std::string &bin_path,
				  const std::string &test_name, bool trace_en,
				  const std::string &expected_uart = "");
bool run_sim(const SimOptions &opts);

static bool env_enabled(const char *name)
{
	const char *value = std::getenv(name);
	return value != nullptr && value[0] != '\0' && std::strcmp(value, "0") != 0;
}

static uint64_t env_u64(const char *name, uint64_t fallback)
{
	const char *value = std::getenv(name);
	if (value == nullptr || value[0] == '\0')
		return fallback;
	char *end = nullptr;
	uint64_t parsed = std::strtoull(value, &end, 0);
	return end != value ? parsed : fallback;
}

struct SimOptions
{
	std::string elf_path = kPayloadElfPath;
	std::string second_elf_path;
	std::string third_elf_path;
	std::string dtb_path;
	std::string test_name = "payload";
	std::string expected_uart;
	std::string flash_image;
	bool trace_wave = false;
	bool direct_elf_load = true;
	bool uart_stdin = false;
	bool trace_irq = false;
	bool trace_dmi = false;
	bool perf_report = false;
	bool jtag_only = false;
	bool require_sram_entry = false;
	bool require_payload_entry = false;
	int jtag_rbb_port = 0;
	bool inject_boot_args = false;
	uint64_t boot_a0 = 0;
	uint64_t boot_a1 = 0;
	uint64_t boot_a2 = 0;
	uint64_t dtb_addr = SRAM_BASE + 0x00f00000;
	uint64_t sram_base = SRAM_BASE;
	size_t sram_size = DEFAULT_SRAM_SIZE;
	uint64_t max_cycles = MAX_SIM_CYCLES;
};

static void clear_ext_irq_sources(VSoc *dut)
{
	dut->io_ext_irq_sources_0 = 0;
	dut->io_ext_irq_sources_1 = 0;
	dut->io_ext_irq_sources_2 = 0;
	dut->io_ext_irq_sources_3 = 0;
	dut->io_ext_irq_sources_4 = 0;
	dut->io_ext_irq_sources_5 = 0;
	dut->io_ext_irq_sources_6 = 0;
	dut->io_ext_irq_sources_7 = 0;
	dut->io_ext_irq_sources_8 = 0;
	dut->io_ext_irq_sources_9 = 0;
	dut->io_ext_irq_sources_10 = 0;
	dut->io_ext_irq_sources_11 = 0;
	dut->io_ext_irq_sources_12 = 0;
	dut->io_ext_irq_sources_13 = 0;
	dut->io_ext_irq_sources_14 = 0;
	dut->io_ext_irq_sources_15 = 0;
	dut->io_ext_irq_sources_16 = 0;
	dut->io_ext_irq_sources_17 = 0;
	dut->io_ext_irq_sources_18 = 0;
	dut->io_ext_irq_sources_19 = 0;
	dut->io_ext_irq_sources_20 = 0;
	dut->io_ext_irq_sources_21 = 0;
	dut->io_ext_irq_sources_22 = 0;
	dut->io_ext_irq_sources_23 = 0;
	dut->io_ext_irq_sources_24 = 0;
	dut->io_ext_irq_sources_25 = 0;
	dut->io_ext_irq_sources_26 = 0;
	dut->io_ext_irq_sources_27 = 0;
	dut->io_ext_irq_sources_28 = 0;
	dut->io_ext_irq_sources_29 = 0;
	dut->io_ext_irq_sources_30 = 0;
	dut->io_ext_irq_sources_31 = 0;
}

static void set_ext_irq_source(VSoc *dut, unsigned source, bool value)
{
	switch (source)
	{
	case 0: dut->io_ext_irq_sources_0 = value; break;
	case 1: dut->io_ext_irq_sources_1 = value; break;
	case 2: dut->io_ext_irq_sources_2 = value; break;
	case 3: dut->io_ext_irq_sources_3 = value; break;
	case 4: dut->io_ext_irq_sources_4 = value; break;
	case 5: dut->io_ext_irq_sources_5 = value; break;
	case 6: dut->io_ext_irq_sources_6 = value; break;
	case 7: dut->io_ext_irq_sources_7 = value; break;
	case 8: dut->io_ext_irq_sources_8 = value; break;
	case 9: dut->io_ext_irq_sources_9 = value; break;
	case 10: dut->io_ext_irq_sources_10 = value; break;
	case 11: dut->io_ext_irq_sources_11 = value; break;
	case 12: dut->io_ext_irq_sources_12 = value; break;
	case 13: dut->io_ext_irq_sources_13 = value; break;
	case 14: dut->io_ext_irq_sources_14 = value; break;
	case 15: dut->io_ext_irq_sources_15 = value; break;
	case 16: dut->io_ext_irq_sources_16 = value; break;
	case 17: dut->io_ext_irq_sources_17 = value; break;
	case 18: dut->io_ext_irq_sources_18 = value; break;
	case 19: dut->io_ext_irq_sources_19 = value; break;
	case 20: dut->io_ext_irq_sources_20 = value; break;
	case 21: dut->io_ext_irq_sources_21 = value; break;
	case 22: dut->io_ext_irq_sources_22 = value; break;
	case 23: dut->io_ext_irq_sources_23 = value; break;
	case 24: dut->io_ext_irq_sources_24 = value; break;
	case 25: dut->io_ext_irq_sources_25 = value; break;
	case 26: dut->io_ext_irq_sources_26 = value; break;
	case 27: dut->io_ext_irq_sources_27 = value; break;
	case 28: dut->io_ext_irq_sources_28 = value; break;
	case 29: dut->io_ext_irq_sources_29 = value; break;
	case 30: dut->io_ext_irq_sources_30 = value; break;
	case 31: dut->io_ext_irq_sources_31 = value; break;
	default: break;
	}
}

class UartStdio
{
  public:
	explicit UartStdio(bool enable_stdin)
	    : enable_stdin_(enable_stdin),
	      inject_cycle_(env_u64("ION_UART_RX_CYCLE", UINT64_MAX)),
	      inject_byte_(env_u64("ION_UART_RX_BYTE", 0)),
	      inject_enabled_(inject_cycle_ != UINT64_MAX)
	{
		if (enable_stdin_)
		{
			old_flags_ = fcntl(STDIN_FILENO, F_GETFL, 0);
			if (old_flags_ >= 0)
				fcntl(STDIN_FILENO, F_SETFL, old_flags_ | O_NONBLOCK);
		}
	}

	~UartStdio()
	{
		if (enable_stdin_ && old_flags_ >= 0)
			fcntl(STDIN_FILENO, F_SETFL, old_flags_);
	}

	void drive_rx(VSoc *dut, uint64_t cycle)
	{
		dut->io_uart_rx_valid = 0;
		dut->io_uart_rx_byte = 0;
		if (inject_enabled_ && !injected_ && cycle >= inject_cycle_)
		{
			dut->io_uart_rx_valid = 1;
			dut->io_uart_rx_byte = (uint8_t)(inject_byte_ & 0xff);
			injected_ = true;
			return;
		}
		if (!enable_stdin_)
			return;

		uint8_t ch = 0;
		ssize_t n = read(STDIN_FILENO, &ch, 1);
		if (n == 1)
		{
			dut->io_uart_rx_valid = 1;
			dut->io_uart_rx_byte = ch;
		}
		else if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK)
		{
			perror("uart stdin read");
		}
	}

	void capture_tx(VSoc *dut)
	{
		if (dut->io_uart_tx)
		{
			uint8_t ch = (uint8_t)(dut->io_uart_byte & 0xFF);
			output_.push_back((char)ch);
			putchar(ch);
			fflush(stdout);
		}
	}

	const std::string &output() const { return output_; }

  private:
	bool enable_stdin_ = false;
	int old_flags_ = -1;
	std::string output_;
	uint64_t inject_cycle_ = UINT64_MAX;
	uint64_t inject_byte_ = 0;
	bool inject_enabled_ = false;
	bool injected_ = false;
};

class InterruptModel
{
  public:
	explicit InterruptModel(bool trace_irq)
	    : trace_irq_(trace_irq), env_mask_(env_u64("ION_IRQ_SOURCE_MASK", 0))
	{
	}

	void drive(VSoc *dut, const std::string &test_name, uint64_t cycle)
	{
		clear_ext_irq_sources(dut);
		uint64_t mask = env_mask_;
		if ((test_name == "plic" || test_name == "plic_s") && cycle >= 80)
			mask |= (1ULL << 1);
		for (unsigned source = 0; source < 32; ++source)
			set_ext_irq_source(dut, source, (mask >> source) & 1ULL);
	}

	void sample(VSoc *dut, uint64_t cycle)
	{
		uint8_t mtip = (dut->rootp->SimTop__DOT__clint__DOT__mtimecmp != 0) &&
		               (dut->rootp->SimTop__DOT__clint__DOT__mtime >= dut->rootp->SimTop__DOT__clint__DOT__mtimecmp);
		uint8_t src1 = dut->rootp->io_ext_irq_sources_1;
		uint8_t pending = dut->rootp->SimTop__DOT__core__DOT__interruptPending;
		uint8_t fire = dut->rootp->SimTop__DOT__core__DOT__interrupt_fire;
		uint64_t mcause = dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mcause;
		if (trace_irq_ && (mtip != last_mtip_ || src1 != last_src1_ || pending != last_pending_ || fire != last_fire_))
		{
			printf("[irq %6" PRIu64 "] src1=%u mtip=%u pending=%u fire=%u mcause=0x%016" PRIx64 "\n",
			       cycle, src1, mtip, pending, fire, mcause);
		}
		last_mtip_ = mtip;
		last_src1_ = src1;
		last_pending_ = pending;
		last_fire_ = fire;
	}

  private:
	bool trace_irq_ = false;
	uint64_t env_mask_ = 0;
	uint8_t last_mtip_ = 0xff;
	uint8_t last_src1_ = 0xff;
	uint8_t last_pending_ = 0xff;
	uint8_t last_fire_ = 0xff;
};

class RemoteBitbang
{
  public:
	explicit RemoteBitbang(int port, bool cycle_per_command = false)
	    : port_(port),
	      cycle_per_command_(cycle_per_command),
	      trace_(env_enabled("ION_TRACE_JTAG")),
	      trace_dmi_(env_enabled("ION_TRACE_DMI")) {}

	bool init()
	{
		if (port_ <= 0)
			return true;
		listen_fd_ = socket(AF_INET, SOCK_STREAM, 0);
		if (listen_fd_ < 0)
		{
			perror("jtag socket");
			return false;
		}
		int one = 1;
		setsockopt(listen_fd_, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
		sockaddr_in addr{};
		addr.sin_family = AF_INET;
		addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
		addr.sin_port = htons((uint16_t)port_);
		if (bind(listen_fd_, (sockaddr *)&addr, sizeof(addr)) != 0)
		{
			perror("jtag bind");
			return false;
		}
		if (listen(listen_fd_, 1) != 0)
		{
			perror("jtag listen");
			return false;
		}
		set_nonblock(listen_fd_);
		printf("[jtag]: remote-bitbang listening on 127.0.0.1:%d\n", port_);
		return true;
	}

	~RemoteBitbang()
	{
		if (client_fd_ >= 0)
			close(client_fd_);
		if (listen_fd_ >= 0)
			close(listen_fd_);
	}

	void drive(VSoc *dut)
	{
		if (port_ <= 0)
			return;
		accept_client();
		if (client_fd_ < 0)
			return;

		char ch = 0;
		ssize_t n = recv(client_fd_, &ch, 1, 0);
		if (n == 1)
		{
			handle(dut, ch);
			return;
		}
		if (n == 0)
		{
			close(client_fd_);
			client_fd_ = -1;
			return;
		}
		if (errno == EAGAIN || errno == EWOULDBLOCK)
			return;
		perror("jtag recv");
		close(client_fd_);
		client_fd_ = -1;
	}

  private:
	static void set_nonblock(int fd)
	{
		int flags = fcntl(fd, F_GETFL, 0);
		if (flags >= 0)
			fcntl(fd, F_SETFL, flags | O_NONBLOCK);
	}

	void accept_client()
	{
		if (client_fd_ >= 0 || listen_fd_ < 0)
			return;
		client_fd_ = accept(listen_fd_, nullptr, nullptr);
		if (client_fd_ >= 0)
		{
			set_nonblock(client_fd_);
			printf("[jtag]: remote-bitbang client connected\n");
		}
		else if (errno != EAGAIN && errno != EWOULDBLOCK)
		{
			perror("jtag accept");
		}
	}

	void handle(VSoc *dut, char ch)
	{
		if (ch >= '0' && ch <= '7')
		{
			unsigned value = (unsigned)(ch - '0');
			dut->io_jtag_tck = (value >> 2) & 1;
			dut->io_jtag_tms = (value >> 1) & 1;
			dut->io_jtag_tdi = value & 1;
			step_command_clock(dut);
			if (trace_)
				printf("[jtag-rbb] write %c tck=%u tms=%u tdi=%u tdo=%u\n", ch,
				       (unsigned)dut->io_jtag_tck, (unsigned)dut->io_jtag_tms,
				       (unsigned)dut->io_jtag_tdi, (unsigned)dut->io_jtag_tdo);
		}
		else if (ch == 'R')
		{
			dut->eval();
			char tdo = dut->io_jtag_tdo ? '1' : '0';
			(void)send(client_fd_, &tdo, 1, MSG_NOSIGNAL);
			if (trace_)
				printf("[jtag-rbb] read tdo=%c\n", tdo);
		}
		else if (ch >= 'r' && ch <= 'u')
		{
			bool trst = ((ch - 'r') & 0x2) != 0;
			if (trst)
			{
				dut->io_jtag_tms = 1;
				dut->io_jtag_tck = 0;
				dut->io_jtag_tdi = 0;
			}
			step_command_clock(dut);
			if (trace_)
				printf("[jtag-rbb] reset cmd %c trst=%u\n", ch, (unsigned)trst);
		}
		else if (ch == 'Q')
		{
			close(client_fd_);
			client_fd_ = -1;
		}
	}

	int port_ = 0;
	int listen_fd_ = -1;
	int client_fd_ = -1;
	bool cycle_per_command_ = false;
	bool trace_ = false;
	bool trace_dmi_ = false;
	uint8_t last_dmi_valid_ = 0;

	void step_command_clock(VSoc *dut)
	{
		if (!cycle_per_command_)
			return;
		// The current Chisel TAP samples TCK edges in the SoC clock domain.
		// In JTAG-only mode, advance a full SoC tick for every remote_bitbang
		// drive command so OpenOCD cannot outrun the synchronizer.
		if (dut->clock)
		{
			dut->clock = 0;
			dut->eval();
		}
		dut->clock = 1;
		dut->eval();
		trace_dmi(dut);
		dut->clock = 0;
		dut->eval();
	}

	void trace_dmi(VSoc *dut)
	{
		if (!trace_dmi_)
			return;
		uint8_t dmi_valid = dut->rootp->SimTop__DOT__debugModule_io_dmi_valid_REG;
		if (dmi_valid && !last_dmi_valid_)
		{
			uint64_t dr = dut->rootp->SimTop__DOT__jtag__DOT__drUpdate;
			unsigned op = dr & 0x3;
			unsigned addr = (dr >> 34) & 0x7f;
			uint32_t wdata = (uint32_t)((dr >> 2) & 0xffffffffu);
			uint32_t rdata = dut->rootp->SimTop__DOT___debugModule_io_dmi_rdata;
			uint32_t dmcontrol = dut->rootp->SimTop__DOT__debugModule__DOT__dmcontrol;
			uint32_t dmstatus = dut->rootp->SimTop__DOT__debugModule__DOT__dmstatus;
			printf("[dmi-cmd] op=%u addr=0x%02x wdata=0x%08x rdata=0x%08x dmcontrol=0x%08x dmstatus=0x%08x\n",
			       op, addr, wdata, rdata, dmcontrol, dmstatus);
			fflush(stdout);
		}
		last_dmi_valid_ = dmi_valid;
	}
};

class FlashImage
{
  public:
	bool load(const std::string &path)
	{
		if (path.empty())
			return true;
		FILE *f = fopen(path.c_str(), "rb");
		if (!f)
		{
			perror("flash fopen");
			return false;
		}
		if (fseek(f, 0, SEEK_END) != 0)
		{
			fclose(f);
			return false;
		}
		long size = ftell(f);
		if (size < 0)
		{
			fclose(f);
			return false;
		}
		rewind(f);
		data_.resize((size_t)size);
		size_t got = fread(data_.data(), 1, data_.size(), f);
		fclose(f);
		if (got != data_.size())
		{
			fprintf(stderr, "short flash image read: %zu/%zu\n", got, data_.size());
			return false;
		}
		printf("[flash]: loaded %zu bytes from %s (QSPI pins not connected yet)\n", data_.size(), path.c_str());
		return true;
	}

  private:
	std::vector<uint8_t> data_;
};

int main(int argc, char **argv, char **env)
{
	printf("\n\n");
	bool all_pass = true;
	if (env_enabled("ION_JTAG_ONLY"))
	{
		SimOptions opts;
		opts.test_name = "jtag";
		opts.trace_wave = env_enabled("ION_TRACE_WAVE");
		opts.direct_elf_load = !env_enabled("ION_BOOTROM_ONLY");
		opts.uart_stdin = env_enabled("ION_UART_STDIN");
		opts.trace_irq = env_enabled("ION_TRACE_IRQ");
		opts.trace_dmi = env_enabled("ION_TRACE_DMI");
		opts.perf_report = env_enabled("ION_PERF");
		opts.jtag_only = true;
		opts.jtag_rbb_port = (int)env_u64("ION_JTAG_RBB_PORT", 0);
		opts.max_cycles = env_u64("ION_MAX_CYCLES", 0);
		const char *flash = std::getenv("ION_FLASH_IMAGE");
		if (flash != nullptr)
			opts.flash_image = flash;
		return run_sim(opts) ? 0 : 1;
	}
	else if (argc < 2)
	{
		// 默认测试 payload (假设无后缀)
		all_pass &= run_one_test(kPayloadElfPath, "payload", env_enabled("ION_TRACE_WAVE"), "S!!P");
	}
	else if (std::string(argv[1]) == "--payload")
	{
		std::string name = (argc > 2) ? argv[2] : "payload";
		std::string expected_uart = (argc > 3) ? argv[3] : "";
		std::string elf_path = (argc > 4) ? argv[4] : kPayloadElfPath;
		all_pass &= run_one_test(elf_path, name, env_enabled("ION_TRACE_WAVE"), expected_uart);
	}
	else if (std::string(argv[1]) == "--rustsbi")
	{
		if (argc < 6)
		{
			fprintf(stderr, "usage: %s --rustsbi <trampoline.elf> <firmware.elf> <payload.elf> <dtb>\n", argv[0]);
			return 1;
		}
		SimOptions opts;
		opts.test_name = "rustsbi";
		opts.elf_path = argv[2];
		opts.second_elf_path = argv[3];
		opts.third_elf_path = argv[4];
		opts.dtb_path = argv[5];
		const char *expected_uart = std::getenv("ION_EXPECT_UART");
		opts.expected_uart = expected_uart != nullptr ? expected_uart : "IonSoC SBI smoke";
		opts.trace_wave = env_enabled("ION_TRACE_WAVE");
		opts.direct_elf_load = true;
		opts.uart_stdin = env_enabled("ION_UART_STDIN");
		opts.trace_irq = env_enabled("ION_TRACE_IRQ");
		opts.trace_dmi = env_enabled("ION_TRACE_DMI");
		opts.perf_report = env_enabled("ION_PERF");
		opts.jtag_rbb_port = (int)env_u64("ION_JTAG_RBB_PORT", 0);
		opts.max_cycles = env_u64("ION_MAX_CYCLES", 8000000);
		opts.sram_base = env_u64("ION_SRAM_BASE", FIRMWARE_SRAM_BASE);
		opts.sram_size = (size_t)env_u64("ION_SRAM_SIZE", DEFAULT_FIRMWARE_SRAM_SIZE);
		opts.dtb_addr = env_u64("ION_DTB_ADDR", opts.sram_base + 0x00f00000);
		opts.require_sram_entry = true;
		opts.require_payload_entry = true;
		opts.inject_boot_args = true;
		opts.boot_a0 = env_u64("ION_BOOT_A0", 0);
		opts.boot_a1 = env_u64("ION_BOOT_A1", opts.dtb_addr);
		opts.boot_a2 = env_u64("ION_BOOT_A2", opts.sram_base + 0x00100000);
		all_pass &= run_sim(opts);
	}
	else
	{
		std::vector<std::string> tests;
		bool testAll = false;

		for (int i = 1; i < argc; ++i)
		{
			if (std::string(argv[i]) == "testAll")
			{
				testAll = true;
				break;
			}
			tests.emplace_back(argv[i]);
		}

		const std::string base_dir = "riscv-tests/target/share/riscv-tests/isa/";
		// const std::string base_dir = "simulator/generated/";

		if (testAll)
		{
			for (auto &p : std::filesystem::directory_iterator(base_dir))
			{
				// 查找无后缀的文件 (ELF)
				if (p.path().extension() == "")
				{
					std::string bin = p.path().string();
					std::string name = p.path().stem().string();
					all_pass &= run_one_test(bin, name, false);
				}
			}
		}
		else
		{
			for (auto &t : tests)
			{
				// 构建路径时不再添加 .bin 后缀
				std::string bin_ui = base_dir + "rv64ui-p-" + t;
				std::string bin_um = base_dir + "rv64um-p-" + t;
				std::string bin;

				if (std::filesystem::exists(bin_ui))
				{
					bin = bin_ui;
				}
				else if (std::filesystem::exists(bin_um))
				{
					bin = bin_um;
				}
				else
				{
					printf("Test binary for %s not found.\n", t.c_str());
					all_pass = false;
					continue;
				}

				all_pass &= run_one_test(bin, t, env_enabled("ION_TRACE_WAVE"));
			}
		}
	}

	printf("All simulations finished.\n");
	return all_pass ? 0 : 1;
}

static uint8_t *sram_bytes(VSoc *dut)
{
	return (uint8_t *)&(dut->rootp->SimTop__DOT__sram__DOT__mem_ext__DOT__Memory[0]);
}

static size_t rtl_sram_capacity_bytes(VSoc *dut)
{
	return sizeof(dut->rootp->SimTop__DOT__sram__DOT__mem_ext__DOT__Memory);
}

static void write_sram_bytes(VSoc *dut, uint64_t sram_base, size_t sram_size, uint64_t paddr, const uint8_t *src, size_t len)
{
	if (paddr < sram_base)
	{
		fprintf(stderr, "SRAM write below base: paddr=0x%016" PRIx64 "\n", paddr);
		exit(1);
	}
	uint64_t off = paddr - sram_base;
	if (off + len > sram_size)
	{
		fprintf(stderr, "SRAM write out of range: paddr=0x%016" PRIx64 " len=%zu sram=%zu\n",
		        paddr, len, sram_size);
		exit(1);
	}
	memcpy(sram_bytes(dut) + off, src, len);
}

void load_elf(VSoc *dut, const char *path)
{
	load_elf_to_regions(dut, path, SRAM_BASE, DEFAULT_SRAM_SIZE);
}

void load_elf_to_regions(VSoc *dut, const char *path, uint64_t sram_base, size_t sram_size)
{
	FILE *f = fopen(path, "rb");
	if (!f)
	{
		perror("fopen");
		exit(1);
	}

	// Read ELF64 header (read max sized header; will validate class)
	Elf64_Ehdr ehdr64;
	if (fread(&ehdr64, 1, sizeof(Elf64_Ehdr), f) != sizeof(Elf64_Ehdr))
	{
		fprintf(stderr, "Read ELF header failed: %s\n", path);
		fclose(f);
		exit(1);
	}

	// Check ELF magic
	if (ehdr64.e_ident[EI_MAG0] != ELFMAG0 || ehdr64.e_ident[EI_MAG1] != ELFMAG1 ||
		ehdr64.e_ident[EI_MAG2] != ELFMAG2 || ehdr64.e_ident[EI_MAG3] != ELFMAG3)
	{
		fprintf(stderr, "Not an ELF file: %s\n", path);
		fclose(f);
		exit(1);
	}

	// Only accept little-endian for now
	if (ehdr64.e_ident[EI_DATA] != ELFDATA2LSB)
	{
		fprintf(stderr, "Unsupported ELF endianness (not little-endian): %s\n", path);
		fclose(f);
		exit(1);
	}

	if (ehdr64.e_ident[EI_CLASS] != ELFCLASS64)
	{
		fprintf(stderr, "Unsupported ELF class (not 64-bit): %s\n", path);
		fclose(f);
		exit(1);
	}

	if (ehdr64.e_phoff == 0 || ehdr64.e_phnum == 0)
	{
		fprintf(stderr, "No program headers in ELF: %s\n", path);
		fclose(f);
		exit(1);
	}

	// Read program headers (Elf64_Phdr)
	if (fseeko(f, (off_t)ehdr64.e_phoff, SEEK_SET) != 0)
	{
		perror("fseeko phoff");
		fclose(f);
		exit(1);
	}
	Elf64_Phdr *phdrs = new Elf64_Phdr[ehdr64.e_phnum];
	if (fread(phdrs, sizeof(Elf64_Phdr), ehdr64.e_phnum, f) != (size_t)ehdr64.e_phnum)
	{
		fprintf(stderr, "Read program headers failed\n");
		delete[] phdrs;
		fclose(f);
		exit(1);
	}

	// memory pointers: treat DUT mem[] as word array but provide byte view
	// 原代码使用 32-bit word pointers；这里保留相同写法（假设目标内存为 32-bit word 存储）
	uint32_t *brom_lo_words = (uint32_t *)&(dut->rootp->SimTop__DOT__brom__DOT__loRom__DOT__mem[0]);
	uint32_t *brom_hi_words = (uint32_t *)&(dut->rootp->SimTop__DOT__brom__DOT__hiRom__DOT__mem[0]);
	uint32_t *tlrom_lo_words = (uint32_t *)&(dut->rootp->SimTop__DOT__tlrom__DOT__loRom__DOT__mem[0]);
	uint32_t *tlrom_hi_words = (uint32_t *)&(dut->rootp->SimTop__DOT__tlrom__DOT__hiRom__DOT__mem[0]);
	uint32_t *sram_words = (uint32_t *)sram_bytes(dut);
	std::vector<uint8_t> brom_image(ROM_SIZE, 0);
	uint8_t *brom_bytes = brom_image.data();
	uint8_t *sram_bytes = (uint8_t *)sram_words;

	const size_t rom_bytes_size = (size_t)ROM_SIZE;
	const size_t sram_bytes_size = sram_size;

	// Helper lambda: safe write (reads from file and writes into target_bytes at byte offset)
	auto write_bytes_to_region = [&](uint8_t *target_bytes, size_t region_bytes,
									 uint64_t offset_byte, uint64_t file_off, uint64_t filesz)
	{
		if (filesz == 0)
			return;
		// clamp
		if ((uint64_t)offset_byte + (uint64_t)filesz > region_bytes)
		{
			size_t can = (offset_byte < region_bytes) ? (region_bytes - offset_byte) : 0;
			fprintf(stderr, "Warning: truncating write: offset 0x%016" PRIx64 " filesz %" PRIu64 " -> %zu available\n",
					offset_byte, filesz, can);
			filesz = (uint64_t)can;
			if (filesz == 0)
				return;
		}

		// If both offset and size are 4-byte aligned, do word writes for performance/endianness clarity
		if ((offset_byte % 4 == 0) && (filesz % 4 == 0))
		{
			size_t word_idx = offset_byte / 4;
			size_t words = filesz / 4;
			// seek file
			if (fseeko(f, (off_t)file_off, SEEK_SET) != 0)
			{
				perror("fseeko file_off");
				return;
			}
			for (size_t w = 0; w < words; ++w)
			{
				uint8_t buf[4];
				size_t r = fread(buf, 1, 4, f);
				if (r != 4)
				{
					fprintf(stderr, "Warning: short read in word copy (%zu/%zu)\n", r, (size_t)4);
					break;
				}
				// little-endian assemble
				uint32_t word = (uint32_t)buf[0] | ((uint32_t)buf[1] << 8) | ((uint32_t)buf[2] << 16) | ((uint32_t)buf[3] << 24);
				// write to target words (assume target_bytes is word-addressable as uint32_t array)
				((uint32_t *)target_bytes)[word_idx + w] = word;
			}
		}
		else
		{
			// unaligned or non-word-sized: do byte-wise copy
			if (fseeko(f, (off_t)file_off, SEEK_SET) != 0)
			{
				perror("fseeko file_off");
				return;
			}
			size_t to_copy = (size_t)filesz;
			size_t dest = (size_t)offset_byte;
			const size_t CHUNK = 1024;
			uint8_t tmp[CHUNK];
			while (to_copy > 0)
			{
				size_t c = (to_copy > CHUNK) ? CHUNK : to_copy;
				size_t r = fread(tmp, 1, c, f);
				if (r == 0)
					break;
				memcpy(target_bytes + dest, tmp, r);
				dest += r;
				to_copy -= r;
			}
		}
	};

	auto mirror_rom_to_rtl = [&](uint64_t offset_byte, uint64_t filesz)
	{
		uint64_t end = offset_byte + filesz;
		if (end > rom_bytes_size)
		{
			end = rom_bytes_size;
		}
		for (uint64_t byte = offset_byte; byte < end; byte += 4)
		{
			uint32_t word = ((uint32_t *)brom_bytes)[byte / 4];
			uint64_t word_idx = byte / 4;
			brom_lo_words[word_idx] = word;
			brom_hi_words[word_idx] = word;
			if ((word_idx & 1) == 0)
			{
				tlrom_lo_words[word_idx] = word;
			}
			else
			{
				tlrom_hi_words[word_idx] = word;
			}
		}
	};

	// Iterate program headers
	for (int i = 0; i < (int)ehdr64.e_phnum; ++i)
	{
		Elf64_Phdr &ph = phdrs[i];
		// debug print
		printf("PHDR %d: type=%" PRIu32 " vaddr=0x%016" PRIx64 " off=0x%016" PRIx64 " filesz=%" PRIu64 " memsz=%" PRIu64 "\n",
			   i, (uint32_t)ph.p_type, (uint64_t)ph.p_vaddr, (uint64_t)ph.p_offset, (uint64_t)ph.p_filesz, (uint64_t)ph.p_memsz);

		if (ph.p_type != PT_LOAD)
			continue;

		uint64_t vaddr = (uint64_t)ph.p_vaddr;
		uint64_t filesz = (uint64_t)ph.p_filesz;
		uint64_t memsz = (uint64_t)ph.p_memsz;

		uint8_t *target_bytes = nullptr;
		size_t region_bytes = 0;
		uint64_t offset_in_region = 0;
		const char *region_name = nullptr;

		if (vaddr >= (uint64_t)BROM_BASE && vaddr < (uint64_t)BROM_BASE + (uint64_t)ROM_SIZE)
		{
			target_bytes = brom_bytes;
			region_bytes = rom_bytes_size;
			offset_in_region = vaddr - (uint64_t)BROM_BASE;
			region_name = "ROM";
		}
		else if (vaddr >= sram_base && vaddr < sram_base + (uint64_t)sram_bytes_size)
		{
			target_bytes = sram_bytes;
			region_bytes = sram_bytes_size;
			offset_in_region = vaddr - sram_base;
			region_name = "SRAM";
		}
		else
		{
			printf("Warning: PT_LOAD at vaddr 0x%016" PRIx64 " (filesz=%" PRIu64 ") outside ROM/SRAM -> skip\n", vaddr, filesz);
			continue;
		}

		// write file bytes to region
		if (filesz > 0)
		{
			write_bytes_to_region(target_bytes, region_bytes, offset_in_region, ph.p_offset, filesz);
			if (target_bytes == brom_bytes)
			{
				mirror_rom_to_rtl(offset_in_region, filesz);
			}
			printf("  -> wrote %" PRIu64 " bytes to %s @ offset 0x%016" PRIx64 "\n", filesz, region_name, offset_in_region);
		}

		// zero initialize BSS tail (memsz > filesz)
		if (memsz > filesz)
		{
			uint64_t zero_start = offset_in_region + filesz;
			uint64_t zero_len = memsz - filesz;
			if (zero_start < region_bytes)
			{
				if (zero_start + zero_len > region_bytes)
				{
					zero_len = region_bytes - zero_start;
				}
				if (zero_len > 0)
				{
					memset(target_bytes + (size_t)zero_start, 0, (size_t)zero_len);
					printf("  -> zeroed %" PRIu64 " bytes in %s @ offset 0x%016" PRIx64 "\n", zero_len, region_name, zero_start);
				}
			}
		}
	}

	delete[] phdrs;
	fclose(f);
}

void load_blob_to_sram(VSoc *dut, const char *path, uint64_t paddr, uint64_t sram_base, size_t sram_size)
{
	FILE *f = fopen(path, "rb");
	if (!f)
	{
		perror("blob fopen");
		exit(1);
	}
	if (fseek(f, 0, SEEK_END) != 0)
	{
		perror("blob fseek");
		fclose(f);
		exit(1);
	}
	long size = ftell(f);
	if (size < 0)
	{
		perror("blob ftell");
		fclose(f);
		exit(1);
	}
	rewind(f);
	std::vector<uint8_t> data((size_t)size);
	if (!data.empty() && fread(data.data(), 1, data.size(), f) != data.size())
	{
		fprintf(stderr, "short blob read: %s\n", path);
		fclose(f);
		exit(1);
	}
	fclose(f);
	write_sram_bytes(dut, sram_base, sram_size, paddr, data.data(), data.size());
	printf("[boot]: loaded blob %s -> SRAM 0x%016" PRIx64 " (%zu bytes)\n", path, paddr, data.size());
}

void ram_init(VSoc *dut, size_t sram_size = DEFAULT_SRAM_SIZE)
{
	size_t rtl_capacity = rtl_sram_capacity_bytes(dut);
	if (sram_size > rtl_capacity)
	{
		fprintf(stderr, "Requested SRAM size 0x%zx exceeds RTL SRAM capacity 0x%zx. Rebuild the matching Verilator profile.\n",
		        sram_size, rtl_capacity);
		exit(1);
	}
	const size_t WORDS = sram_size / 8; // Memory is 64-bit wide
	for (size_t i = 0; i < WORDS; ++i)
	{
		dut->rootp->SimTop__DOT__sram__DOT__mem_ext__DOT__Memory[i] = 0x0;
	}
}

bool run_one_test(const std::string &bin_path,
				  const std::string &test_name, bool trace_en,
				  const std::string &expected_uart)
{
	SimOptions opts;
	opts.elf_path = bin_path;
	opts.test_name = test_name;
	opts.expected_uart = expected_uart;
	opts.trace_wave = trace_en;
	opts.direct_elf_load = !env_enabled("ION_BOOTROM_ONLY");
	opts.uart_stdin = env_enabled("ION_UART_STDIN");
	opts.trace_irq = env_enabled("ION_TRACE_IRQ");
	opts.trace_dmi = env_enabled("ION_TRACE_DMI");
	opts.perf_report = env_enabled("ION_PERF");
	opts.jtag_rbb_port = (int)env_u64("ION_JTAG_RBB_PORT", 0);
	opts.max_cycles = env_u64("ION_MAX_CYCLES", MAX_SIM_CYCLES);
	if (test_name == "uart_irq")
		opts.max_cycles = env_u64("ION_MAX_CYCLES", 200000);
	const char *flash = std::getenv("ION_FLASH_IMAGE");
	if (flash != nullptr)
		opts.flash_image = flash;
	return run_sim(opts);
}

bool run_sim(const SimOptions &opts)
{
	VSoc *dut = new VSoc;
#if VM_TRACE
	VerilatedVcdC *tfp = new VerilatedVcdC;
#else
	void *tfp = nullptr;
	if (opts.trace_wave)
		printf("[trace]: ION_TRACE_WAVE requested, but this binary was built without TRACE=1; VCD disabled.\n");
#endif

	sim_time = 0;

	ram_init(dut, opts.sram_size);

	FlashImage flash;
	if (!flash.load(opts.flash_image))
	{
		delete dut;
#if VM_TRACE
		delete tfp;
#endif
		return false;
	}
	if (opts.direct_elf_load)
	{
		load_elf_to_regions(dut, opts.elf_path.c_str(), opts.sram_base, opts.sram_size);
		if (!opts.second_elf_path.empty())
			load_elf_to_regions(dut, opts.second_elf_path.c_str(), opts.sram_base, opts.sram_size);
		if (!opts.third_elf_path.empty())
			load_elf_to_regions(dut, opts.third_elf_path.c_str(), opts.sram_base, opts.sram_size);
		if (!opts.dtb_path.empty())
			load_blob_to_sram(dut, opts.dtb_path.c_str(), opts.dtb_addr, opts.sram_base, opts.sram_size);
	}
	else
	{
		printf("[boot]: direct ELF preload disabled; boot ROM will run from built-in contents\n");
		printf("[boot]: use ION_FLASH_IMAGE for the future QSPI-backed boot path\n");
	}

	clear_ext_irq_sources(dut);
	dut->io_uart_rx_valid = 0;
	dut->io_uart_rx_byte = 0;
	dut->io_jtag_tms = 1;
	dut->io_jtag_tck = 0;
	dut->io_jtag_tdi = 0;

	if (opts.trace_wave)
	{
#if VM_TRACE
		Verilated::traceEverOn(true);
		dut->trace(tfp, 99);
		tfp->open(kWavePath);
#endif
	}
	else
		Verilated::traceEverOn(false);

	dut->clock = 0;
	dut->reset = 1;

	UartStdio uart(opts.uart_stdin);
	InterruptModel irq(opts.trace_irq);
	RemoteBitbang jtag(opts.jtag_rbb_port, opts.jtag_only);
	if (!jtag.init())
	{
		delete dut;
#if VM_TRACE
		delete tfp;
#endif
		return false;
	}

	for (int i = 0; i < 6; ++i)
	{
		irq.drive(dut, opts.test_name, sim_time);
		uart.drive_rx(dut, sim_time);
		jtag.drive(dut);
		dut->clock ^= 1;
		dut->eval();
		if (opts.trace_wave)
#if VM_TRACE
			tfp->dump(sim_time);
#else
			(void)tfp;
#endif
		sim_time++;
	}

	dut->reset = 0;
	if (opts.inject_boot_args)
	{
		dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[10] = opts.boot_a0;
		dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[11] = opts.boot_a1;
		dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[12] = opts.boot_a2;
		printf("[boot]: sram_base=0x%016" PRIx64 " sram_size=0x%zx dtb=0x%016" PRIx64 " payload=0x%016" PRIx64 "\n",
		       opts.sram_base, opts.sram_size, opts.dtb_addr, opts.boot_a2);
		printf("[boot]: injected a0=0x%016" PRIx64 " a1=0x%016" PRIx64 " a2=0x%016" PRIx64 "\n",
		       opts.boot_a0, opts.boot_a1, opts.boot_a2);
	}

	printf("\n--- UART output ---\n");
	bool saw_exit = false;
	bool trace_cpu = std::getenv("ION_TRACE_CPU") != nullptr;
	uint64_t trace_pc_start = env_u64("ION_TRACE_PC_START", 0);
	uint64_t trace_pc_end = env_u64("ION_TRACE_PC_END", UINT64_MAX);
	bool trace_boot = env_enabled("ION_TRACE_BOOT");
	bool saw_rom_pc = false;
	bool saw_sram_pc = false;
	bool saw_payload_pc = false;
	uint64_t last_pc = UINT64_MAX;
	uint64_t last_mtimecmp = UINT64_MAX;
	uint8_t last_mtip = 0xff;
	uint8_t last_dmi_valid = 0;
	uint64_t perf_cycles = 0;
	uint64_t perf_retired = 0;
	uint64_t perf_stall_cycles = 0;
	uint64_t perf_ifetch_stall_cycles = 0;
	uint64_t perf_ifetch_only_stall_cycles = 0;
	uint64_t perf_ifetch_lsu_overlap_cycles = 0;
	uint64_t perf_lsu_stall_cycles = 0;
	uint64_t perf_lsu_load_stall_cycles = 0;
	uint64_t perf_lsu_store_stall_cycles = 0;
	uint64_t perf_lsu_mmio_stall_cycles = 0;
	uint64_t perf_lsu_atomic_stall_cycles = 0;
	uint64_t perf_lsu_fence_stall_cycles = 0;
	uint64_t perf_branch_count = 0;
	uint64_t perf_branch_taken = 0;
	uint64_t perf_branch_redirect = 0;
	uint64_t perf_branch_pred_taken = 0;
	uint64_t perf_branch_pred_correct = 0;

	while (opts.max_cycles == 0 || sim_time < opts.max_cycles)
	{
		irq.drive(dut, opts.test_name, sim_time);
		uart.drive_rx(dut, sim_time);
		jtag.drive(dut);

		dut->clock ^= 1;
		dut->eval();
		if (opts.trace_wave)
#if VM_TRACE
			tfp->dump(sim_time);
#else
			(void)tfp;
#endif

		uint64_t cur_mtimecmp = dut->rootp->SimTop__DOT__clint__DOT__mtimecmp;
		uint8_t cur_mtip = (dut->rootp->SimTop__DOT__clint__DOT__mtimecmp != 0) &&
						   (dut->rootp->SimTop__DOT__clint__DOT__mtime >= dut->rootp->SimTop__DOT__clint__DOT__mtimecmp);
		bool trace_state_change = cur_mtip != last_mtip || cur_mtimecmp != last_mtimecmp;
		uint64_t pc_now = dut->io_debug_pc;
		if (dut->clock)
		{
			perf_cycles++;
			perf_retired += dut->io_debug_retire ? 1 : 0;
			perf_stall_cycles += dut->io_debug_stall ? 1 : 0;
			perf_ifetch_stall_cycles += dut->io_debug_ifetchStall ? 1 : 0;
			perf_lsu_stall_cycles += dut->io_debug_lsuStall ? 1 : 0;
			perf_ifetch_only_stall_cycles += (dut->io_debug_ifetchStall && !dut->io_debug_lsuStall) ? 1 : 0;
			perf_ifetch_lsu_overlap_cycles += (dut->io_debug_ifetchStall && dut->io_debug_lsuStall) ? 1 : 0;
			perf_lsu_load_stall_cycles += dut->io_debug_lsuLoadStall ? 1 : 0;
			perf_lsu_store_stall_cycles += dut->io_debug_lsuStoreStall ? 1 : 0;
			perf_lsu_mmio_stall_cycles += dut->io_debug_lsuMmioStall ? 1 : 0;
			perf_lsu_atomic_stall_cycles += dut->io_debug_lsuAtomicStall ? 1 : 0;
			perf_lsu_fence_stall_cycles += dut->io_debug_lsuFenceStall ? 1 : 0;
			perf_branch_count += dut->io_debug_branchValid ? 1 : 0;
			perf_branch_taken += dut->io_debug_branchTaken ? 1 : 0;
			perf_branch_redirect += dut->io_debug_branchRedirect ? 1 : 0;
			perf_branch_pred_taken += dut->io_debug_branchPredTaken ? 1 : 0;
			perf_branch_pred_correct += dut->io_debug_branchPredCorrect ? 1 : 0;
		}

		if (dut->clock)
		{
			if (!saw_rom_pc && pc_now >= BROM_BASE && pc_now < BROM_BASE + ROM_SIZE)
			{
				saw_rom_pc = true;
				if (trace_boot)
					printf("[boot-trace %6" PRIu64 "] entered ROM pc=0x%016" PRIx64 "\n", sim_time, pc_now);
			}
			if (!saw_sram_pc && pc_now >= opts.sram_base && pc_now < opts.sram_base + opts.sram_size)
			{
				saw_sram_pc = true;
				if (trace_boot)
					printf("[boot-trace %6" PRIu64 "] entered SRAM pc=0x%016" PRIx64 "\n", sim_time, pc_now);
			}
			if (!saw_payload_pc && pc_now >= opts.boot_a2 && pc_now < opts.boot_a2 + 0x10000)
			{
				saw_payload_pc = true;
				if (trace_boot)
					printf("[boot-trace %6" PRIu64 "] entered payload pc=0x%016" PRIx64 "\n", sim_time, pc_now);
			}
			if (trace_boot && dut->rootp->SimTop__DOT__core__DOT__combined_trap)
			{
				printf("[boot-trace %6" PRIu64 "] trap pc=0x%016" PRIx64 " mtvec=0x%016" PRIx64
				       " mepc=0x%016" PRIx64 " mcause=0x%016" PRIx64 "\n",
				       sim_time,
				       pc_now,
				       (uint64_t)dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mtvec,
				       (uint64_t)dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mepc,
				       (uint64_t)dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mcause);
			}
		}

		bool trace_pc_match = pc_now >= trace_pc_start && pc_now <= trace_pc_end;
		if (trace_cpu && trace_pc_match && dut->clock && (sim_time < 150 || dut->io_debug_pc != last_pc || trace_state_change))
		{
			last_pc = pc_now;
			last_mtimecmp = cur_mtimecmp;
			last_mtip = cur_mtip;
			uint64_t ra = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[1];
			uint64_t t0 = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[5];
			uint64_t s0 = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[8];
			uint64_t t3 = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[28];
			uint64_t a0_trace = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[10];
			uint64_t a7_trace = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[17];
			printf("[trace %6" PRIu64 "] pc=0x%016" PRIx64 " instr=0x%08x ra=0x%016" PRIx64 " t0=0x%016" PRIx64 " s0=0x%016" PRIx64 " t3=0x%016" PRIx64 " a0=0x%016" PRIx64 " a7=0x%016" PRIx64
				   " id_v=%u id_rd=%u id_w=%u id_op1=0x%016" PRIx64 " id_op2=0x%016" PRIx64
				   " lsu_stall=%u load_valid=%u load=0x%016" PRIx64 " alu_v=%u alu_rd=%u alu_w=%u alu_res=0x%016" PRIx64 " alu_pc=0x%016" PRIx64 " alu_op1=0x%016" PRIx64 " alu_op2=0x%016" PRIx64
				   " lsu_v=%u lsu_rd=%u lsu_w=%u lsu_res=0x%016" PRIx64 " wb_rd=%u wb_w=%u wb_data=0x%016" PRIx64
					   " if_state=%u if_acc=%u if_req_pc=0x%016" PRIx64 " if_pc=0x%016" PRIx64 " if_instr=0x%08x if_len=%u if_rel_len=%u pc_step=%u pc_hold=%u bpu_taken=%u"
				   " redirect=%u int_p=%u int_f=%u trap=%u flush=%u mtvec=0x%016" PRIx64 " mepc=0x%016" PRIx64 " mcause=0x%016" PRIx64
				   " mstatus=0x%016" PRIx64 " mie=0x%016" PRIx64 " plic_src1=%u mtip=%u mtime=0x%016" PRIx64 " mtimecmp=0x%016" PRIx64 "\n",
				   sim_time,
				   (uint64_t)dut->io_debug_pc,
				   (uint32_t)dut->io_debug_instr,
				   ra,
				   t0,
				   s0,
				   t3,
				   a0_trace,
				   a7_trace,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__idecode__DOT__io_valid_out_r,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__idecode__DOT__io_decoded_out_r_rd,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__idecode__DOT__io_decoded_out_r_ctrl_reg_write,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__idecode__DOT__io_decoded_out_r_op1,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__idecode__DOT__io_decoded_out_r_op2,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__lsu__DOT__io_stall_req_0,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT___lsu_io_load_data_valid,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT___lsu_io_load_data,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__alu__DOT__io_valid_out_r,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__alu__DOT__io_alu_out_rd_r,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__alu__DOT__io_alu_out_reg_write_r,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__alu__DOT__io_alu_out_result_r,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__alu__DOT__io_pc_out_r,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__alu__DOT__op1,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__alu__DOT__op2,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__lsu__DOT__io_valid_out_REG,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__lsu__DOT__out_reg_rd,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__lsu__DOT__out_reg_write,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__lsu__DOT__out_result,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT___wb_io_reg_wb_rd,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT___wb_io_reg_wb_reg_write,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT___wb_io_reg_wb_data,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__ifetch__DOT__state,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__ifetch__DOT__acceptResp,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__ifetch__DOT__reqPc,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__ifetch__DOT__io_pc_out_r_1,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__ifetch__DOT__io_instr_out_r_1,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__ifetch__DOT__acceptedLen,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__ifetch__DOT__releaseLen,
					   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__ifetch__DOT__releaseLen,
					   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__pc__DOT__redirectHold,
					   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__pc__DOT___bpu_io_pred_taken,
					   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__pc__DOT__redirect,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__interruptPending,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__interrupt_fire,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__combined_trap,
				   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__combined_trap,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mtvec,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mepc,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mcause,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mstatus,
				   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mie,
				   (uint32_t)dut->rootp->io_ext_irq_sources_1,
				   (uint32_t)cur_mtip,
				   (uint64_t)dut->rootp->SimTop__DOT__clint__DOT__mtime,
				   cur_mtimecmp);
		}
		irq.sample(dut, sim_time);
		if (opts.trace_dmi)
		{
			uint8_t dmi_valid = dut->rootp->SimTop__DOT__debugModule_io_dmi_valid_REG;
			if (dmi_valid && !last_dmi_valid)
			{
				uint64_t dr = dut->rootp->SimTop__DOT__jtag__DOT__drUpdate;
				unsigned op = dr & 0x3;
				unsigned addr = (dr >> 34) & 0x7f;
				uint32_t wdata = (uint32_t)((dr >> 2) & 0xffffffffu);
				uint32_t rdata = dut->rootp->SimTop__DOT___debugModule_io_dmi_rdata;
				uint32_t dmcontrol = dut->rootp->SimTop__DOT__debugModule__DOT__dmcontrol;
				uint32_t dmstatus = dut->rootp->SimTop__DOT__debugModule__DOT__dmstatus;
				printf("[dmi %6" PRIu64 "] op=%u addr=0x%02x wdata=0x%08x rdata=0x%08x dmcontrol=0x%08x dmstatus=0x%08x\n",
				       sim_time, op, addr, wdata, rdata, dmcontrol, dmstatus);
			}
			last_dmi_valid = dmi_valid;
		}

		if (dut->clock)
			uart.capture_tx(dut);

		int a0_live = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[10];
		int a7_live = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[17];
		if (!opts.jtag_only && a7_live == 93)
		{
			saw_exit = true;
			sim_time++;
			break;
		}

		sim_time++;
	}

	printf("\n--- UART output end ---\n");

	int gp = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[3];
	int a0 = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[10];
	int a7 = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[17];
	if (trace_boot)
	{
		printf("[boot-trace end] cycles=%" PRIu64 " pc=0x%016" PRIx64 " instr=0x%08x mtvec=0x%016" PRIx64
		       " mepc=0x%016" PRIx64 " mcause=0x%016" PRIx64 " a0=0x%016" PRIx64 " a1=0x%016" PRIx64
		       " a2=0x%016" PRIx64 " a7=0x%016" PRIx64
		       " fence_start=%u fence_pending=%u fence_issued=%u fence_active=%u fence_ack=%u\n",
		       sim_time,
		       (uint64_t)dut->io_debug_pc,
		       (uint32_t)dut->io_debug_instr,
		       (uint64_t)dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mtvec,
		       (uint64_t)dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mepc,
		       (uint64_t)dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mcause,
		       (uint64_t)dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[10],
		       (uint64_t)dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[11],
		       (uint64_t)dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[12],
		       (uint64_t)dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[17],
		       (uint32_t)dut->rootp->SimTop__DOT__core__DOT__fenceIStart,
		       (uint32_t)dut->rootp->SimTop__DOT__core__DOT__fenceIPending,
		       (uint32_t)dut->rootp->SimTop__DOT__core__DOT__fenceIFlushIssued,
		       (uint32_t)dut->rootp->SimTop__DOT__core__DOT__fenceIActive,
		       (uint32_t)dut->rootp->SimTop__DOT__core__DOT__fenceIAck);
		if (dut->rootp->__PVT__SimTop__DOT__core__DOT__icache != nullptr)
		{
			printf("[boot-trace cache] ifetch_state=%u ifetch_req=%u icache_state=%u icache_req_ready=%u icache_resp_valid=%u icache_inv_ready=%u bus_a_valid=%u bus_d_ready=%u\n",
			       (uint32_t)dut->rootp->SimTop__DOT__core__DOT__ifetch__DOT__state,
			       (uint32_t)dut->rootp->SimTop__DOT__core__DOT__ifetch__DOT__io_cache_req_valid_0,
			       (uint32_t)dut->rootp->__PVT__SimTop__DOT__core__DOT__icache->state,
			       (uint32_t)dut->rootp->__PVT__SimTop__DOT__core__DOT__icache->io_cpu_req_ready,
			       (uint32_t)dut->rootp->__PVT__SimTop__DOT__core__DOT__icache->io_cpu_resp_valid,
			       (uint32_t)dut->rootp->__PVT__SimTop__DOT__core__DOT__icache->io_invalidate_ready,
			       (uint32_t)dut->rootp->__PVT__SimTop__DOT__core__DOT__icache->io_bus_a_valid,
			       (uint32_t)dut->rootp->__PVT__SimTop__DOT__core__DOT__icache->io_bus_d_ready);
		}
	}

	bool uart_pass = opts.expected_uart.empty() || (uart.output().find(opts.expected_uart) != std::string::npos);
	bool boot_flow_pass = (!opts.require_sram_entry || saw_sram_pc) &&
	                      (!opts.require_payload_entry || saw_payload_pc);
	bool pass = opts.jtag_only ? true : (saw_exit && a7 == 93 && a0 == 0 && uart_pass && boot_flow_pass);
	if (opts.perf_report)
	{
		double ipc = perf_cycles == 0 ? 0.0 : (double)perf_retired / (double)perf_cycles;
		double stall_pct = perf_cycles == 0 ? 0.0 : (100.0 * (double)perf_stall_cycles / (double)perf_cycles);
		double ifetch_pct = perf_cycles == 0 ? 0.0 : (100.0 * (double)perf_ifetch_stall_cycles / (double)perf_cycles);
		double lsu_pct = perf_cycles == 0 ? 0.0 : (100.0 * (double)perf_lsu_stall_cycles / (double)perf_cycles);
		double branch_rate = perf_retired == 0 ? 0.0 : (100.0 * (double)perf_branch_count / (double)perf_retired);
		double branch_taken_pct = perf_branch_count == 0 ? 0.0 : (100.0 * (double)perf_branch_taken / (double)perf_branch_count);
		double branch_redirect_pct = perf_branch_count == 0 ? 0.0 : (100.0 * (double)perf_branch_redirect / (double)perf_branch_count);
		double branch_pred_taken_pct = perf_branch_count == 0 ? 0.0 : (100.0 * (double)perf_branch_pred_taken / (double)perf_branch_count);
		double branch_pred_correct_pct = perf_branch_count == 0 ? 0.0 : (100.0 * (double)perf_branch_pred_correct / (double)perf_branch_count);
		printf("[perf]: cycles=%" PRIu64 " retired=%" PRIu64 " ipc=%.4f stall_cycles=%" PRIu64 " stall_pct=%.2f ifetch_stall=%" PRIu64 " ifetch_pct=%.2f lsu_stall=%" PRIu64 " lsu_pct=%.2f\n",
		       perf_cycles,
		       perf_retired,
		       ipc,
		       perf_stall_cycles,
		       stall_pct,
		       perf_ifetch_stall_cycles,
		       ifetch_pct,
		       perf_lsu_stall_cycles,
		       lsu_pct);
		printf("[perf-branch]: branches=%" PRIu64 " branch_rate=%.2f taken=%" PRIu64 " taken_pct=%.2f redirects=%" PRIu64 " redirect_pct=%.2f pred_taken=%" PRIu64 " pred_taken_pct=%.2f pred_correct=%" PRIu64 " pred_correct_pct=%.2f\n",
		       perf_branch_count,
		       branch_rate,
		       perf_branch_taken,
		       branch_taken_pct,
		       perf_branch_redirect,
		       branch_redirect_pct,
		       perf_branch_pred_taken,
		       branch_pred_taken_pct,
		       perf_branch_pred_correct,
		       branch_pred_correct_pct);
		printf("[perf-lsu]: load=%" PRIu64 " store=%" PRIu64 " mmio=%" PRIu64 " atomic=%" PRIu64 " fence=%" PRIu64 "\n",
		       perf_lsu_load_stall_cycles,
		       perf_lsu_store_stall_cycles,
		       perf_lsu_mmio_stall_cycles,
		       perf_lsu_atomic_stall_cycles,
		       perf_lsu_fence_stall_cycles);
		printf("[perf-overlap]: ifetch_only=%" PRIu64 " ifetch_lsu_overlap=%" PRIu64 "\n",
		       perf_ifetch_only_stall_cycles,
		       perf_ifetch_lsu_overlap_cycles);
	}

	if (opts.jtag_only)
		printf("[%s]: JTAG server active on port %d%s\n", opts.test_name.c_str(), opts.jtag_rbb_port, CEND);
	else if (pass)
		printf("[%s]: gp=%d, a7=%d, a0=%d, test %spassed%s\n", opts.test_name.c_str(), gp, a7, a0, GREEN, CEND);
	else if (a7 == 93)
		printf("[%s]: gp=%d, a7=%d, a0=%d, uart=\"%s\", test %sfailed%s\n", opts.test_name.c_str(), gp, a7, a0, uart.output().c_str(), RED, CEND);
	else
		printf("[%s]: gp=%d, a7=%d, a0=%d, test %sunknown%s\n", opts.test_name.c_str(), gp, a7, a0, YELLOW, CEND);

	if (!opts.jtag_only && !pass)
	{
		printf("[sim-fail]: saw_exit=%u uart_pass=%u boot_flow_pass=%u entered_sram=%u entered_payload=%u expected_uart=\"%s\"\n",
		       saw_exit ? 1 : 0,
		       uart_pass ? 1 : 0,
		       boot_flow_pass ? 1 : 0,
		       saw_sram_pc ? 1 : 0,
		       saw_payload_pc ? 1 : 0,
		       opts.expected_uart.c_str());
		printf("[sim-fail]: cycles=%" PRIu64 " pc=0x%016" PRIx64 " instr=0x%08x mtvec=0x%016" PRIx64
		       " mepc=0x%016" PRIx64 " mcause=0x%016" PRIx64 " mtval=0x%016" PRIx64 "\n",
		       sim_time,
		       (uint64_t)dut->io_debug_pc,
		       (uint32_t)dut->io_debug_instr,
		       (uint64_t)dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mtvec,
		       (uint64_t)dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mepc,
		       (uint64_t)dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mcause,
		       (uint64_t)dut->rootp->SimTop__DOT__core__DOT__csr__DOT__mtval);
	}

	if (opts.trace_wave)
#if VM_TRACE
		tfp->close();
#else
		(void)tfp;
#endif
	dut->final();
	delete dut;
#if VM_TRACE
	delete tfp;
#endif
	return pass;
}
