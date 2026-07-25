JC          := javac
JAVA        := java
SRC_DIR     := src
OUT_DIR     := out
EX_DIR      := examples
MAIN        := Cpu16Asm

SOURCES     := $(wildcard $(SRC_DIR)/*.java)
BUILD_STAMP := $(OUT_DIR)/$(MAIN).class
EXAMPLES    := $(wildcard $(EX_DIR)/*.asm)

ASM ?= $(EX_DIR)/allops.asm
OUT ?= $(OUT_DIR)/$(notdir $(basename $(ASM))).bin
FORMAT ?= bin

ifeq ($(OS),Windows_NT)
    MKDIR = if not exist "$(1)" mkdir "$(1)"
    RMDIR = if exist "$(OUT_DIR)" rmdir /s /q "$(OUT_DIR)"
else
    MKDIR = mkdir -p "$(1)"
    RMDIR = rm -rf "$(OUT_DIR)"
endif

.PHONY: all build run debug test formats clean help

all: build

build: $(BUILD_STAMP)

$(BUILD_STAMP): $(SOURCES)
	@$(call MKDIR,$(OUT_DIR))
	$(JC) -d $(OUT_DIR) $(SOURCES)

run: build
	@$(call MKDIR,$(dir $(OUT)))
	$(JAVA) -cp $(OUT_DIR) $(MAIN) $(ASM) $(OUT) --format=$(FORMAT)

debug: build
	@$(call MKDIR,$(dir $(OUT)))
	$(JAVA) -cp $(OUT_DIR) $(MAIN) $(ASM) $(OUT) --format=$(FORMAT) --debug

test: build
	@echo "== assembling all examples =="
	@$(call MKDIR,$(OUT_DIR)/test)
	@ok=1; \
	for f in $(EXAMPLES); do \
		name=$$(basename $$f .asm); \
		echo "-- $$f"; \
		$(JAVA) -cp $(OUT_DIR) $(MAIN) $$f $(OUT_DIR)/test/$$name.bin || ok=0; \
	done; \
	if [ $$ok -eq 1 ]; then echo "All examples assembled OK"; else echo "Some examples FAILED"; exit 1; fi

formats: build
	@$(call MKDIR,$(OUT_DIR)/formats)
	$(JAVA) -cp $(OUT_DIR) $(MAIN) $(ASM) $(OUT_DIR)/formats/out.bin --format=bin
	$(JAVA) -cp $(OUT_DIR) $(MAIN) $(ASM) $(OUT_DIR)/formats/out.hex --format=hex
	$(JAVA) -cp $(OUT_DIR) $(MAIN) $(ASM) $(OUT_DIR)/formats/out.manual.txt --format=manual
	@echo "ไฟล์ผลลัพธ์อยู่ที่ $(OUT_DIR)/formats/"

clean:
	@$(RMDIR)

help:
	@grep -E '^## ' $(MAKEFILE_LIST) | sed 's/^## /  /'