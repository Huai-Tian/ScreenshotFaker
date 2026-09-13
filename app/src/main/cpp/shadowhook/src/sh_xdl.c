// Copyright (c) 2021-2026 ByteDance Inc.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//

// Created by Kelun Cai (caikelun@bytedance.com) on 2026-01-07.

#include "sh_xdl.h"

#include <pthread.h>
#include <string.h>

#include "sh_errno.h"
#include "sh_log.h"
#include "sh_sig.h"
#include "sh_util.h"
#include "shadowhook.h"
#include "xdl.h"

typedef struct {
  const char *lib_name;
  void *dlpi_addr;
  const char *dlpi_name;
  const ElfW(Phdr) *dlpi_phdr;
  size_t dlpi_phnum;
  pthread_mutex_t lock;
} sh_xdl_handle_info_t;

#define TO_STR_HELPER(x)    #x
#define TO_STR(x)           TO_STR_HELPER(x)
#define INIT_ITEM(lib_name) {TO_STR(lib_name), 0, NULL, NULL, 0, PTHREAD_MUTEX_INITIALIZER}

static sh_xdl_handle_info_t sh_xdl_handle_info_cache[] = {
#if defined(__arm__)
    INIT_ITEM(linker),
#elif defined(__aarch64__)
    INIT_ITEM(linker64),
#endif
    INIT_ITEM(libc.so),
    INIT_ITEM(libart.so),
    INIT_ITEM(libhwui.so),
    INIT_ITEM(libandroidfw.so),
    INIT_ITEM(libandroid_runtime.so),
    INIT_ITEM(libbinder.so)};

static bool sh_xdl_match_pathname(const char *pathname, const char *lib_name) {
  if ('/' == pathname[0]) {
    if (!sh_util_ends_with(pathname, lib_name)) return false;
  } else {
    if (0 != strcmp(pathname, lib_name)) return false;
  }

  return true;
}

static void *sh_xdl_open_from_linker(const char *lib_name) {
  void *handle = NULL;

  if (SHADOWHOOK_ERRNO_OK != shadowhook_get_init_errno() || sh_util_get_api_level() >= __ANDROID_API_L__) {
    handle = xdl_open(lib_name, XDL_DEFAULT);
  } else {
    SH_SIG_TRY(SIGSEGV, SIGBUS) {
      handle = xdl_open(lib_name, XDL_DEFAULT);
    }
    SH_SIG_CATCH() {
      handle = SH_XDL_CRASH;
    }
    SH_SIG_EXIT
  }

  SH_LOG_INFO("xdl: open from linker %s, %s", lib_name,
              (NULL == handle || SH_XDL_CRASH == handle) ? "failed" : "ok");
  return handle;
}

static void *sh_xdl_open_from_cache(sh_xdl_handle_info_t *info, void *dlpi_addr) {
  struct dl_phdr_info dlinfo;
  dlinfo.dlpi_addr = (ElfW(Addr))dlpi_addr;
  dlinfo.dlpi_name = __atomic_load_n(&info->dlpi_name, __ATOMIC_RELAXED);
  dlinfo.dlpi_phdr = __atomic_load_n(&info->dlpi_phdr, __ATOMIC_RELAXED);
  dlinfo.dlpi_phnum = (ElfW(Half))__atomic_load_n(&info->dlpi_phnum, __ATOMIC_RELAXED);

  void *handle = xdl_open2(&dlinfo);
  SH_LOG_INFO("xdl: open from cache %s, %s", dlinfo.dlpi_name, NULL == handle ? "failed" : "ok");
  return handle;
}

void *sh_xdl_open(const char *lib_name) {
  for (size_t i = 0; i < sizeof(sh_xdl_handle_info_cache) / sizeof(sh_xdl_handle_info_cache[0]); i++) {
    sh_xdl_handle_info_t *info = &sh_xdl_handle_info_cache[i];
    if (sh_xdl_match_pathname(lib_name, info->lib_name)) {
      void *dlpi_addr = __atomic_load_n(&info->dlpi_addr, __ATOMIC_ACQUIRE);
      if (0 != dlpi_addr) {
        // already loaded
        return sh_xdl_open_from_cache(info, dlpi_addr);
      } else {
        void *handle;
        pthread_mutex_lock(&info->lock);

        dlpi_addr = __atomic_load_n(&info->dlpi_addr, __ATOMIC_RELAXED);
        if (0 != dlpi_addr) {
          // already loaded
          handle = sh_xdl_open_from_cache(info, dlpi_addr);
        } else {
          handle = sh_xdl_open_from_linker(lib_name);
          if (NULL != handle && SH_XDL_CRASH != handle) {
            xdl_info_t dlinfo;
            xdl_info(handle, XDL_DI_DLINFO, &dlinfo);
            char *name = strdup(dlinfo.dli_fname);
            if (NULL != name) {
              __atomic_store_n(&info->dlpi_name, name, __ATOMIC_RELAXED);
              __atomic_store_n(&info->dlpi_phdr, dlinfo.dlpi_phdr, __ATOMIC_RELAXED);
              __atomic_store_n(&info->dlpi_phnum, dlinfo.dlpi_phnum, __ATOMIC_RELAXED);
              __atomic_store_n(&info->dlpi_addr, dlinfo.dli_fbase, __ATOMIC_RELEASE);
              SH_LOG_INFO("xdl: save to cache %s", info->dlpi_name);
            }
          }
        }

        pthread_mutex_unlock(&info->lock);
        return handle;
      }
    }
  }

  return sh_xdl_open_from_linker(lib_name);
}

void *sh_xdl_sym(void *handle, const char *sym_name, size_t *symbol_size) {
  void *addr = NULL;
  if (SHADOWHOOK_ERRNO_OK != shadowhook_get_init_errno()) {
    addr = xdl_sym(handle, sym_name, symbol_size);
    if (NULL == addr) addr = xdl_dsym(handle, sym_name, symbol_size);
  } else {
    SH_SIG_TRY(SIGSEGV, SIGBUS) {
      addr = xdl_sym(handle, sym_name, symbol_size);
      if (NULL == addr) addr = xdl_dsym(handle, sym_name, symbol_size);
    }
    SH_SIG_CATCH() {
      addr = SH_XDL_CRASH;
    }
    SH_SIG_EXIT
  }
  return addr;
}

void *sh_xdl_sym_dynsym(void *handle, const char *sym_name, size_t *symbol_size) {
  void *addr = NULL;
  if (SHADOWHOOK_ERRNO_OK != shadowhook_get_init_errno()) {
    addr = xdl_sym(handle, sym_name, symbol_size);
  } else {
    SH_SIG_TRY(SIGSEGV, SIGBUS) {
      addr = xdl_sym(handle, sym_name, symbol_size);
    }
    SH_SIG_CATCH() {
      addr = SH_XDL_CRASH;
    }
    SH_SIG_EXIT
  }
  return addr;
}

void *sh_xdl_sym_symtab(void *handle, const char *sym_name, size_t *symbol_size) {
  void *addr = NULL;
  if (SHADOWHOOK_ERRNO_OK != shadowhook_get_init_errno()) {
    addr = xdl_dsym(handle, sym_name, symbol_size);
  } else {
    SH_SIG_TRY(SIGSEGV, SIGBUS) {
      addr = xdl_dsym(handle, sym_name, symbol_size);
    }
    SH_SIG_CATCH() {
      addr = SH_XDL_CRASH;
    }
    SH_SIG_EXIT
  }
  return addr;
}
