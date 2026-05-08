#include <cstdio>
#include <cstring> // Added for strcmp
#include <string>
#include <sys/stat.h>
#include <filesystem>
#include <elf.h>
#include <cstdlib>
#include <cstdint>
#include <verilated.h>
#include <verilated_vcd_c.h>
#include "VSoc.h"
#include "VSoc___024root.h"

#define RED "\033[31m"
#define GREEN "\033[32m"
#define YELLOW "\033[33m"
#define CEND "\033[0m"

#define BROM_BASE 0x80000000
#define ROM_SIZE 0x10000 // 64KB = 16384 words * 4 bytes
#define SRAM_BASE 0x10000000
#define SRAM_SIZE 0x10000

#define MAX_SIM_CYCLES 10000
static const char *kPayloadElfPath = "simulator/build/payload/payload.elf";
static const char *kWavePath = "simulator/build/wave.vcd";
vluint64_t sim_time = 0;

void load_elf(VSoc *dut, const char *path);
bool run_one_test(const std::string &bin_path,
                  const std::string &test_name, bool trace_en,
                  const std::string &expected_uart = "");

int main(int argc, char **argv, char **env)
{
    printf("\n\n");
    bool all_pass = true;
    if (argc < 2)
    {
        // 默认测试 payload (假设无后缀)
        all_pass &= run_one_test(kPayloadElfPath, "payload", true, "S!!P");
    }
    else if (std::string(argv[1]) == "--payload")
    {
        std::string name = (argc > 2) ? argv[2] : "payload";
        std::string expected_uart = (argc > 3) ? argv[3] : "";
        std::string elf_path = (argc > 4) ? argv[4] : kPayloadElfPath;
        all_pass &= run_one_test(elf_path, name, true, expected_uart);
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

                all_pass &= run_one_test(bin, t, true);
            }
        }
    }

    printf("All simulations finished.\n");
    return all_pass ? 0 : 1;
}

void load_elf(VSoc *dut, const char *path)
{
    FILE *f = fopen(path, "rb");
    if (!f) { perror("fopen"); exit(1); }

    // Read ELF64 header (read max sized header; will validate class)
    Elf64_Ehdr ehdr64;
    if (fread(&ehdr64, 1, sizeof(Elf64_Ehdr), f) != sizeof(Elf64_Ehdr)) {
        fprintf(stderr, "Read ELF header failed: %s\n", path);
        fclose(f); exit(1);
    }

    // Check ELF magic
    if (ehdr64.e_ident[EI_MAG0] != ELFMAG0 || ehdr64.e_ident[EI_MAG1] != ELFMAG1 ||
        ehdr64.e_ident[EI_MAG2] != ELFMAG2 || ehdr64.e_ident[EI_MAG3] != ELFMAG3) {
        fprintf(stderr, "Not an ELF file: %s\n", path);
        fclose(f); exit(1);
    }

    // Only accept little-endian for now
    if (ehdr64.e_ident[EI_DATA] != ELFDATA2LSB) {
        fprintf(stderr, "Unsupported ELF endianness (not little-endian): %s\n", path);
        fclose(f); exit(1);
    }

    if (ehdr64.e_ident[EI_CLASS] != ELFCLASS64) {
        fprintf(stderr, "Unsupported ELF class (not 64-bit): %s\n", path);
        fclose(f); exit(1);
    }

    if (ehdr64.e_phoff == 0 || ehdr64.e_phnum == 0) {
        fprintf(stderr, "No program headers in ELF: %s\n", path);
        fclose(f); exit(1);
    }

    // Read program headers (Elf64_Phdr)
    if (fseeko(f, (off_t)ehdr64.e_phoff, SEEK_SET) != 0) { perror("fseeko phoff"); fclose(f); exit(1); }
    Elf64_Phdr *phdrs = new Elf64_Phdr[ehdr64.e_phnum];
    if (fread(phdrs, sizeof(Elf64_Phdr), ehdr64.e_phnum, f) != (size_t)ehdr64.e_phnum) {
        fprintf(stderr, "Read program headers failed\n");
        delete[] phdrs; fclose(f); exit(1);
    }

    // memory pointers: treat DUT mem[] as word array but provide byte view
    // 原代码使用 32-bit word pointers；这里保留相同写法（假设目标内存为 32-bit word 存储）
    uint32_t *brom_words = (uint32_t*)&(dut->rootp->SimTop__DOT__brom__DOT__rom__DOT__mem[0]);
    uint32_t *tlrom_lo_words = (uint32_t*)&(dut->rootp->SimTop__DOT__tlrom__DOT__loRom__DOT__mem[0]);
    uint32_t *tlrom_hi_words = (uint32_t*)&(dut->rootp->SimTop__DOT__tlrom__DOT__hiRom__DOT__mem[0]);
    uint32_t *sram_words = (uint32_t*)&(dut->rootp->SimTop__DOT__sram__DOT__mem_ext__DOT__Memory[0]);
    uint8_t *brom_bytes = (uint8_t*)brom_words;
    uint8_t *sram_bytes = (uint8_t*)sram_words;

    const size_t rom_bytes_size = (size_t)ROM_SIZE;
    const size_t sram_bytes_size = (size_t)SRAM_SIZE;

    // Helper lambda: safe write (reads from file and writes into target_bytes at byte offset)
    auto write_bytes_to_region = [&](uint8_t *target_bytes, size_t region_bytes,
                                    uint64_t offset_byte, uint64_t file_off, uint64_t filesz) {
        if (filesz == 0) return;
        // clamp
        if ((uint64_t)offset_byte + (uint64_t)filesz > region_bytes) {
            size_t can = (offset_byte < region_bytes) ? (region_bytes - offset_byte) : 0;
            fprintf(stderr, "Warning: truncating write: offset 0x%016" PRIx64 " filesz %" PRIu64 " -> %zu available\n",
                    offset_byte, filesz, can);
            filesz = (uint64_t)can;
            if (filesz == 0) return;
        }

        // If both offset and size are 4-byte aligned, do word writes for performance/endianness clarity
        if ((offset_byte % 4 == 0) && (filesz % 4 == 0)) {
            size_t word_idx = offset_byte / 4;
            size_t words = filesz / 4;
            // seek file
            if (fseeko(f, (off_t)file_off, SEEK_SET) != 0) { perror("fseeko file_off"); return; }
            for (size_t w = 0; w < words; ++w) {
                uint8_t buf[4];
                size_t r = fread(buf, 1, 4, f);
                if (r != 4) { fprintf(stderr, "Warning: short read in word copy (%zu/%zu)\n", r, (size_t)4); break; }
                // little-endian assemble
                uint32_t word = (uint32_t)buf[0] | ((uint32_t)buf[1] << 8) | ((uint32_t)buf[2] << 16) | ((uint32_t)buf[3] << 24);
                // write to target words (assume target_bytes is word-addressable as uint32_t array)
                ((uint32_t*)target_bytes)[word_idx + w] = word;
            }
        } else {
            // unaligned or non-word-sized: do byte-wise copy
            if (fseeko(f, (off_t)file_off, SEEK_SET) != 0) { perror("fseeko file_off"); return; }
            size_t to_copy = (size_t)filesz;
            size_t dest = (size_t)offset_byte;
            const size_t CHUNK = 1024;
            uint8_t tmp[CHUNK];
            while (to_copy > 0) {
                size_t c = (to_copy > CHUNK) ? CHUNK : to_copy;
                size_t r = fread(tmp, 1, c, f);
                if (r == 0) break;
                memcpy(target_bytes + dest, tmp, r);
                dest += r;
                to_copy -= r;
            }
        }
    };

    auto mirror_rom_to_tlrom = [&](uint64_t offset_byte, uint64_t filesz) {
        uint64_t end = offset_byte + filesz;
        if (end > rom_bytes_size) {
            end = rom_bytes_size;
        }
        for (uint64_t byte = offset_byte; byte < end; byte += 4) {
            uint32_t word = ((uint32_t*)brom_bytes)[byte / 4];
            uint64_t word_idx = byte / 4;
            if ((word_idx & 1) == 0) {
                tlrom_lo_words[word_idx] = word;
            } else {
                tlrom_hi_words[word_idx] = word;
            }
        }
    };

    // Iterate program headers
    for (int i = 0; i < (int)ehdr64.e_phnum; ++i) {
        Elf64_Phdr &ph = phdrs[i];
        // debug print
        printf("PHDR %d: type=%" PRIu32 " vaddr=0x%016" PRIx64 " off=0x%016" PRIx64 " filesz=%" PRIu64 " memsz=%" PRIu64 "\n",
               i, (uint32_t)ph.p_type, (uint64_t)ph.p_vaddr, (uint64_t)ph.p_offset, (uint64_t)ph.p_filesz, (uint64_t)ph.p_memsz);

        if (ph.p_type != PT_LOAD) continue;

        uint64_t vaddr = (uint64_t)ph.p_vaddr;
        uint64_t filesz = (uint64_t)ph.p_filesz;
        uint64_t memsz = (uint64_t)ph.p_memsz;

        uint8_t *target_bytes = nullptr;
        size_t region_bytes = 0;
        uint64_t offset_in_region = 0;
        const char *region_name = nullptr;

        if (vaddr >= (uint64_t)BROM_BASE && vaddr < (uint64_t)BROM_BASE + (uint64_t)ROM_SIZE) {
            target_bytes = brom_bytes;
            region_bytes = rom_bytes_size;
            offset_in_region = vaddr - (uint64_t)BROM_BASE;
            region_name = "ROM";
        } else if (vaddr >= (uint64_t)SRAM_BASE && vaddr < (uint64_t)SRAM_BASE + (uint64_t)SRAM_SIZE) {
            target_bytes = sram_bytes;
            region_bytes = sram_bytes_size;
            offset_in_region = vaddr - (uint64_t)SRAM_BASE;
            region_name = "SRAM";
        } else {
            printf("Warning: PT_LOAD at vaddr 0x%016" PRIx64 " (filesz=%" PRIu64 ") outside ROM/SRAM -> skip\n", vaddr, filesz);
            continue;
        }

        // write file bytes to region
        if (filesz > 0) {
            write_bytes_to_region(target_bytes, region_bytes, offset_in_region, ph.p_offset, filesz);
            if (target_bytes == brom_bytes) {
                mirror_rom_to_tlrom(offset_in_region, filesz);
            }
            printf("  -> wrote %" PRIu64 " bytes to %s @ offset 0x%016" PRIx64 "\n", filesz, region_name, offset_in_region);
        }

        // zero initialize BSS tail (memsz > filesz)
        if (memsz > filesz) {
            uint64_t zero_start = offset_in_region + filesz;
            uint64_t zero_len = memsz - filesz;
            if (zero_start < region_bytes) {
                if (zero_start + zero_len > region_bytes) {
                    zero_len = region_bytes - zero_start;
                }
                if (zero_len > 0) {
                    memset(target_bytes + (size_t)zero_start, 0, (size_t)zero_len);
                    printf("  -> zeroed %" PRIu64 " bytes in %s @ offset 0x%016" PRIx64 "\n", zero_len, region_name, zero_start);
                }
            }
        }
    }

    delete[] phdrs;
    fclose(f);
}

void ram_init(VSoc *dut)
{
    const size_t WORDS = SRAM_SIZE / 8; // Memory is 64-bit wide
    for (int i = 0; i < WORDS; ++i)
    {
        dut->rootp->SimTop__DOT__sram__DOT__mem_ext__DOT__Memory[i] = 0x0;
    }
}

bool run_one_test(const std::string &bin_path,
                  const std::string &test_name, bool trace_en,
                  const std::string &expected_uart)
{
    VSoc *dut = new VSoc;
    VerilatedVcdC *tfp = new VerilatedVcdC;

    sim_time = 0;

    ram_init(dut);
    load_elf(dut, bin_path.c_str());
#define CLEAR_EXT_IRQ_SOURCES(DUT) do { \
    (DUT)->io_ext_irq_sources_0 = 0;  (DUT)->io_ext_irq_sources_1 = 0;  (DUT)->io_ext_irq_sources_2 = 0;  (DUT)->io_ext_irq_sources_3 = 0; \
    (DUT)->io_ext_irq_sources_4 = 0;  (DUT)->io_ext_irq_sources_5 = 0;  (DUT)->io_ext_irq_sources_6 = 0;  (DUT)->io_ext_irq_sources_7 = 0; \
    (DUT)->io_ext_irq_sources_8 = 0;  (DUT)->io_ext_irq_sources_9 = 0;  (DUT)->io_ext_irq_sources_10 = 0; (DUT)->io_ext_irq_sources_11 = 0; \
    (DUT)->io_ext_irq_sources_12 = 0; (DUT)->io_ext_irq_sources_13 = 0; (DUT)->io_ext_irq_sources_14 = 0; (DUT)->io_ext_irq_sources_15 = 0; \
    (DUT)->io_ext_irq_sources_16 = 0; (DUT)->io_ext_irq_sources_17 = 0; (DUT)->io_ext_irq_sources_18 = 0; (DUT)->io_ext_irq_sources_19 = 0; \
    (DUT)->io_ext_irq_sources_20 = 0; (DUT)->io_ext_irq_sources_21 = 0; (DUT)->io_ext_irq_sources_22 = 0; (DUT)->io_ext_irq_sources_23 = 0; \
    (DUT)->io_ext_irq_sources_24 = 0; (DUT)->io_ext_irq_sources_25 = 0; (DUT)->io_ext_irq_sources_26 = 0; (DUT)->io_ext_irq_sources_27 = 0; \
    (DUT)->io_ext_irq_sources_28 = 0; (DUT)->io_ext_irq_sources_29 = 0; (DUT)->io_ext_irq_sources_30 = 0; (DUT)->io_ext_irq_sources_31 = 0; \
} while (0)

    CLEAR_EXT_IRQ_SOURCES(dut);
    dut->io_uart_rx_valid = 0;
    dut->io_uart_rx_byte = 0;

    if (trace_en)
    {
        Verilated::traceEverOn(true);
        dut->trace(tfp, 99);
        tfp->open(kWavePath);
    }
    else
        Verilated::traceEverOn(false);

    dut->clock = 0;
    dut->reset = 1;

    for (int i = 0; i < 6; ++i)
    {
        CLEAR_EXT_IRQ_SOURCES(dut);
        dut->io_uart_rx_valid = 0;
        dut->io_uart_rx_byte = 0;
        dut->clock ^= 1;
        dut->eval();
        tfp->dump(sim_time);
        sim_time++;
    }

    dut->reset = 0;

	    bool prev_uart_tx = false;
	    std::string uart_output;
	    printf("\n--- UART output ---\n");
    bool saw_exit = false;
    bool trace_cpu = std::getenv("ION_TRACE_CPU") != nullptr;
    uint64_t last_pc = UINT64_MAX;
    uint64_t last_mtimecmp = UINT64_MAX;
    uint8_t last_mtip = 0xff;

    while (sim_time < MAX_SIM_CYCLES)
    {
        CLEAR_EXT_IRQ_SOURCES(dut);
        dut->io_uart_rx_valid = 0;
        dut->io_uart_rx_byte = 0;
        dut->io_ext_irq_sources_1 = ((test_name == "plic" || test_name == "plic_s") && sim_time >= 80) ? 1 : 0;

        dut->clock ^= 1;
        dut->eval();
        tfp->dump(sim_time);

        uint64_t cur_mtimecmp = dut->rootp->SimTop__DOT__clint__DOT__mtimecmp;
        uint8_t cur_mtip = (dut->rootp->SimTop__DOT__clint__DOT__mtimecmp != 0) &&
                            (dut->rootp->SimTop__DOT__clint__DOT__mtime >= dut->rootp->SimTop__DOT__clint__DOT__mtimecmp);
        bool trace_state_change = cur_mtip != last_mtip || cur_mtimecmp != last_mtimecmp;

        if (trace_cpu && dut->clock && (sim_time < 150 || dut->io_debug_pc != last_pc || trace_state_change)) {
            last_pc = dut->io_debug_pc;
            last_mtimecmp = cur_mtimecmp;
            last_mtip = cur_mtip;
            uint64_t t0 = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[5];
            uint64_t t3 = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[28];
            uint64_t a0_trace = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[10];
            uint64_t a7_trace = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[17];
            printf("[trace %6" PRIu64 "] pc=0x%016" PRIx64 " instr=0x%08x t0=0x%016" PRIx64 " t3=0x%016" PRIx64 " a0=0x%016" PRIx64 " a7=0x%016" PRIx64
                   " lsu_stall=%u load_valid=%u load=0x%016" PRIx64 " alu_rd=%u alu_w=%u alu_op1=0x%016" PRIx64 " alu_op2=0x%016" PRIx64
                   " br_v=%u br_t=%u br_target=0x%016" PRIx64 " redirect=%u int_p=%u int_f=%u trap=%u flush=%u mtvec=0x%016" PRIx64 " mepc=0x%016" PRIx64 " mcause=0x%016" PRIx64
                   " mstatus=0x%016" PRIx64 " mie=0x%016" PRIx64 " plic_src1=%u mtip=%u mtime=0x%016" PRIx64 " mtimecmp=0x%016" PRIx64 "\n",
                   sim_time,
                   (uint64_t)dut->io_debug_pc,
                   (uint32_t)dut->io_debug_instr,
                   t0,
                   t3,
                   a0_trace,
                   a7_trace,
                   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__lsu__DOT__io_stall_req_0,
                   (uint32_t)dut->rootp->SimTop__DOT__core__DOT___lsu_io_load_data_valid,
                   (uint64_t)dut->rootp->SimTop__DOT__core__DOT___lsu_io_load_data,
                   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__alu__DOT__io_alu_out_rd_r,
                   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__alu__DOT__io_alu_out_reg_write_r,
                   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__alu__DOT__op1,
                   (uint64_t)dut->rootp->SimTop__DOT__core__DOT__alu__DOT__op2,
                   (uint32_t)dut->rootp->SimTop__DOT__core__DOT___alu_io_br_info_valid,
                   (uint32_t)dut->rootp->SimTop__DOT__core__DOT___alu_io_br_info_taken,
                   (uint64_t)dut->rootp->SimTop__DOT__core__DOT___alu_io_br_info_target,
                   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__pc__DOT__redirect,
                   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__interruptPending,
                   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__interrupt_fire,
                   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__combined_trap,
                   (uint32_t)dut->rootp->SimTop__DOT__core__DOT__pipeline_flush,
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

        // UART TX: print char when tx_valid rises
        bool cur_uart_tx = dut->io_uart_tx;
        if (cur_uart_tx && !prev_uart_tx) {
            uint8_t ch = (uint8_t)(dut->io_uart_byte & 0xFF);
            uart_output.push_back((char)ch);
            putchar(ch);
            fflush(stdout);
	        }
	        prev_uart_tx = cur_uart_tx;
	
        int a0_live = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[10];
        int a7_live = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[17];
        if (a7_live == 93) {
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

    bool uart_pass = expected_uart.empty() || (uart_output.find(expected_uart) != std::string::npos);
    bool pass = (saw_exit && a7 == 93 && a0 == 0 && uart_pass);

    if (pass)
        printf("[%s]: gp=%d, a7=%d, a0=%d, test %spassed%s\n", test_name.c_str(), gp, a7, a0, GREEN, CEND);
    else if (a7 == 93)
        printf("[%s]: gp=%d, a7=%d, a0=%d, uart=\"%s\", test %sfailed%s\n", test_name.c_str(), gp, a7, a0, uart_output.c_str(), RED, CEND);
    else
        printf("[%s]: gp=%d, a7=%d, a0=%d, test %sunknown%s\n", test_name.c_str(), gp, a7, a0, YELLOW, CEND);

    tfp->close();
    dut->final();
    delete dut;
    delete tfp;
    return pass;
}
