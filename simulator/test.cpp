#include <cstdio>
#include <cstring> // Added for strcmp
#include <sys/stat.h>
#include <filesystem>
#include <elf.h>
#include <verilated.h>
#include <verilated_vcd_c.h>
#include "VSoc.h"
#include "VSoc___024root.h"

#define RED "\033[31m"
#define GREEN "\033[32m"
#define YELLOW "\033[33m"
#define CEND "\033[0m"

#define BROM_BASE 0x00000000
#define ROM_SIZE 0x1000
// #define SRAM_BASE 0x20000000
#define SRAM_BASE 0x1000
#define SRAM_SIZE 0x4000

#define MAX_SIM_CYCLES 10000
vluint64_t sim_time = 0;

void load_elf(VSoc *dut, const char *path);
bool run_one_test(const std::string &bin_path,
                  const std::string &test_name, bool trace_en);

int main(int argc, char **argv, char **env)
{
    printf("\n\n");
    if (argc < 2)
    {
        // 默认测试 payload (假设无后缀)
        run_one_test("simulator/build/payload/payload.elf", "payload", true);
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

        // const std::string base_dir = "simulator/riscv-tests/isa/";
        const std::string base_dir = "simulator/generated/";

        if (testAll)
        {
            for (auto &p : std::filesystem::directory_iterator(base_dir))
            {
                // 查找无后缀的文件 (ELF)
                if (p.path().extension() == "")
                {
                    std::string bin = p.path().string();
                    std::string name = p.path().stem().string();
                    run_one_test(bin, name, false);
                }
            }
        }
        else
        {
            for (auto &t : tests)
            {
                // 构建路径时不再添加 .bin 后缀
                std::string bin_ui = base_dir + "rv32ui-p-" + t;
                std::string bin_um = base_dir + "rv32um-p-" + t;
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
                    continue;
                }

                run_one_test(bin, t, true);
            }
        }
    }

    printf("All simulations finished.\n");
    return 0;
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
    uint32_t *rom_words = (uint32_t*)&(dut->rootp->SimTop__DOT__brom__DOT__rom__DOT__mem[0]);
    uint32_t *sram_words = (uint32_t*)&(dut->rootp->SimTop__DOT__sram__DOT__mem_ext__DOT__Memory[0]);
    uint8_t *rom_bytes = (uint8_t*)rom_words;
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
            target_bytes = rom_bytes;
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
    const size_t WORDS = SRAM_SIZE / 4;
    for (int i = 0; i < WORDS; ++i)
    {
        dut->rootp->SimTop__DOT__sram__DOT__mem_ext__DOT__Memory[i] = 0;
    }
}

bool run_one_test(const std::string &bin_path,
                  const std::string &test_name, bool trace_en)
{
    VSoc *dut = new VSoc;
    VerilatedVcdC *tfp = new VerilatedVcdC;

    sim_time = 0;

    ram_init(dut);
    load_elf(dut, bin_path.c_str());

    if (trace_en)
    {
        Verilated::traceEverOn(true);
        dut->trace(tfp, 99);
        tfp->open("simulator/build/wave.vcd");
    }
    else
        Verilated::traceEverOn(false);

    dut->clock = 0;
    dut->reset = 1;

    for (int i = 0; i < 6; ++i)
    {
        dut->clock ^= 1;
        dut->eval();
        tfp->dump(sim_time);
        sim_time++;
    }

    dut->reset = 0;

    // bool finished = false;
    // while (sim_time < MAX_SIM_CYCLES && !finished)
    while (sim_time < MAX_SIM_CYCLES)
    {
        dut->clock ^= 1;
        dut->eval();
        tfp->dump(sim_time);

        // if (dut->dbg_exit)
        //     finished = true;

        sim_time++;
    }

    int x3 = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[3];
    int x26 = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[26];
    int x27 = dut->rootp->SimTop__DOT__core__DOT__register__DOT__regFile_ext__DOT__Memory[27];

    bool pass = (x26 == 1 && x27 == 1);

    if (x26 == 1 && x27 == 1)
        printf("[%s]: x3=%d, x26=%d, x27=%d, test %ssuccess%s\n", test_name.c_str(), x3, x26, x27, GREEN, CEND);
    else if (x26 == 1 && x27 == 0)
        printf("[%s]: x3=%d, x26=%d, x27=%d, test %sfailed%s\n", test_name.c_str(), x3, x26, x27, RED, CEND);
    else
        printf("[%s]: x3=%d, x26=%d, x27=%d, test %sunknown%s\n", test_name.c_str(), x3, x26, x27, YELLOW, CEND);

    tfp->close();
    dut->final();
    delete dut;
    delete tfp;
    return pass;
}

