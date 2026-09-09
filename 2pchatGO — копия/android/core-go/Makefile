# Set ANDROID_NDK_HOME (or pass NDK_DIR=/path/to/ndk) in local and CI builds.
# Do not hard-code a developer's macOS SDK path: emulator builds also run on Windows.
NDK_DIR ?= $(ANDROID_NDK_HOME)

ifeq ($(OS),Windows_NT)
NDK_HOST_TAG ?= windows-x86_64
ANDROID_CLANG = $(TOOLCHAIN)/clang.exe
ANDROID_ARM64_CC = $(ANDROID_CLANG) --target=aarch64-linux-android$(API_LEVEL)
ANDROID_X86_64_CC = $(ANDROID_CLANG) --target=x86_64-linux-android$(API_LEVEL)
ANDROID_ARM_CC = $(ANDROID_CLANG) --target=armv7a-linux-androideabi$(API_LEVEL)
else
UNAME_S := $(shell uname -s)
ifeq ($(UNAME_S),Darwin)
NDK_HOST_TAG ?= darwin-x86_64
else
NDK_HOST_TAG ?= linux-x86_64
endif
ANDROID_ARM64_CC = $(TOOLCHAIN)/aarch64-linux-android$(API_LEVEL)-clang
ANDROID_X86_64_CC = $(TOOLCHAIN)/x86_64-linux-android$(API_LEVEL)-clang
ANDROID_ARM_CC = $(TOOLCHAIN)/armv7a-linux-androideabi$(API_LEVEL)-clang
endif

ifeq ($(strip $(NDK_DIR)),)
$(error Set ANDROID_NDK_HOME or pass NDK_DIR=/path/to/android-ndk)
endif

TOOLCHAIN = $(NDK_DIR)/toolchains/llvm/prebuilt/$(NDK_HOST_TAG)/bin
API_LEVEL = 24
JNILIBS_DIR = ../app/src/main/jniLibs
CGO_CFLAGS ?= -fno-omit-frame-pointer -O2
CGO_LDFLAGS ?= -Wl,-z,max-page-size=16384

.PHONY: all test clean android-arm64 android-x86_64 android-arm android-all

all: test android-all

test:
	go test -v ./pkg/...

android-arm64:
	mkdir -p $(JNILIBS_DIR)/arm64-v8a
	CGO_ENABLED=1 \
	CGO_CFLAGS="$(CGO_CFLAGS)" \
	CGO_LDFLAGS="$(CGO_LDFLAGS)" \
	GOOS=android \
	GOARCH=arm64 \
	CC="$(ANDROID_ARM64_CC)" \
	go build -ldflags="-s -w -extldflags '$(CGO_LDFLAGS)'" -buildmode=c-shared -o $(JNILIBS_DIR)/arm64-v8a/lib2pcore.so ./cmd/lib2pcore
	@rm -f $(JNILIBS_DIR)/arm64-v8a/lib2pcore.h
	@echo "✓ Built arm64-v8a: $(JNILIBS_DIR)/arm64-v8a/lib2pcore.so"

android-x86_64:
	mkdir -p $(JNILIBS_DIR)/x86_64
	CGO_ENABLED=1 \
	CGO_CFLAGS="$(CGO_CFLAGS)" \
	CGO_LDFLAGS="$(CGO_LDFLAGS)" \
	GOOS=android \
	GOARCH=amd64 \
	CC="$(ANDROID_X86_64_CC)" \
	go build -ldflags="-s -w -extldflags '$(CGO_LDFLAGS)'" -buildmode=c-shared -o $(JNILIBS_DIR)/x86_64/lib2pcore.so ./cmd/lib2pcore
	@rm -f $(JNILIBS_DIR)/x86_64/lib2pcore.h
	@echo "✓ Built x86_64: $(JNILIBS_DIR)/x86_64/lib2pcore.so"

android-arm:
	mkdir -p $(JNILIBS_DIR)/armeabi-v7a
	CGO_ENABLED=1 \
	CGO_CFLAGS="$(CGO_CFLAGS)" \
	CGO_LDFLAGS="$(CGO_LDFLAGS)" \
	GOOS=android \
	GOARCH=arm \
	GOARM=7 \
	CC="$(ANDROID_ARM_CC)" \
	go build -ldflags="-s -w -extldflags '$(CGO_LDFLAGS)'" -buildmode=c-shared -o $(JNILIBS_DIR)/armeabi-v7a/lib2pcore.so ./cmd/lib2pcore
	@rm -f $(JNILIBS_DIR)/armeabi-v7a/lib2pcore.h
	@echo "✓ Built armeabi-v7a: $(JNILIBS_DIR)/armeabi-v7a/lib2pcore.so"

android-all: android-arm64 android-x86_64 android-arm
	@echo "✓ All Android native binaries built successfully."

clean:
	rm -rf $(JNILIBS_DIR)/arm64-v8a/lib2pcore.so $(JNILIBS_DIR)/x86_64/lib2pcore.so $(JNILIBS_DIR)/armeabi-v7a/lib2pcore.so
