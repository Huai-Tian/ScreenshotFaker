// Copyright (c) 2021-2025 ByteDance Inc.
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

// Created by Kelun Cai (caikelun@bytedance.com) on 2021-04-11.

#include "sh_task.h"

#include <inttypes.h>
#include <malloc.h>
#include <poll.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <unistd.h>

#include "queue.h"
#include "sh_config.h"
#include "sh_errno.h"
#include "sh_linker.h"
#include "sh_log.h"
#include "sh_recorder.h"
#include "sh_ref.h"
#include "sh_sig.h"
#include "sh_switch.h"
#include "sh_util.h"
#include "shadowhook.h"
#include "xdl.h"

typedef enum { SH_TASK_HOOK, SH_TASK_INTERCEPT } sh_task_type_t;

// task
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wpadded"
struct sh_task {
  char *lib_name;
  char *sym_name;
  char *record_lib_name;
  char *record_sym_name;
  uintptr_t target_addr;
  sh_task_type_t type;
  union {
    struct {
      uintptr_t new_addr;
      uintptr_t *orig_addr;
      size_t flags;
      shadowhook_hooked_t hooked;
      void *hooked_arg;
    } hook;
    struct {
      shadowhook_interceptor_t pre;
      void *data;
      size_t flags;
      shadowhook_intercepted_t intercepted;
      void *intercepted_arg;
    } intercept;
  } typed;
  uintptr_t caller_addr;
  bool is_by_target_addr;
  bool is_sym_addr;
  bool is_proc_start;
  bool is_finished;
  bool is_corrupted;
  sh_ref_t ref;
  TAILQ_ENTRY(sh_task, ) link;
  TAILQ_ENTRY(sh_task, ) link_tmp;
};
#pragma clang diagnostic pop

// task queue
typedef TAILQ_HEAD(sh_task_queue, sh_task, ) sh_task_queue_t;

// task queue object
static sh_task_queue_t sh_tasks = TAILQ_HEAD_INITIALIZER(sh_tasks);
static pthread_mutex_t sh_tasks_lock = PTHREAD_MUTEX_INITIALIZER;
static int sh_tasks_unfinished_cnt = 0;

sh_task_t *sh_task_create_hook_by_target_addr(uintptr_t target_addr, uintptr_t new_addr, uintptr_t *orig_addr,
                                              uint32_t flags, bool is_sym_addr, bool is_proc_start,
                                              uintptr_t caller_addr, char *record_lib_name,
                                              char *record_sym_name) {
  sh_task_t *self = calloc(1, sizeof(sh_task_t));
  if (NULL == self) return NULL;
  self->lib_name = NULL;
  self->sym_name = NULL;
  if (NULL != record_lib_name) {
    if (NULL == (self->record_lib_name = strdup(record_lib_name))) goto err;
  } else {
    self->record_lib_name = NULL;
  }
  if (NULL != record_sym_name) {
    if (NULL == (self->record_sym_name = strdup(record_sym_name))) goto err;
  } else {
    self->record_sym_name = NULL;
  }
  self->target_addr = target_addr;
  self->type = SH_TASK_HOOK;
  self->typed.hook.new_addr = new_addr;
  self->typed.hook.orig_addr = orig_addr;
  self->typed.hook.flags = (size_t)flags;
  self->typed.hook.hooked = NULL;
  self->typed.hook.hooked_arg = NULL;
  self->caller_addr = caller_addr;
  self->is_by_target_addr = true;
  self->is_sym_addr = is_sym_addr;
  self->is_proc_start = is_proc_start;
  self->is_finished = false;
  self->is_corrupted = false;
  sh_ref_init(&self->ref);
  return self;

err:
  sh_task_destroy(self);
  return NULL;
}

sh_task_t *sh_task_create_hook_by_sym_name(const char *lib_name, const char *sym_name, uintptr_t new_addr,
                                           uintptr_t *orig_addr, uint32_t flags, shadowhook_hooked_t hooked,
                                           void *hooked_arg, uintptr_t caller_addr) {
  sh_task_t *self = calloc(1, sizeof(sh_task_t));
  if (NULL == self) return NULL;
  if (NULL == (self->lib_name = strdup(lib_name))) goto err;
  if (NULL == (self->sym_name = strdup(sym_name))) goto err;
  self->record_lib_name = NULL;
  self->record_sym_name = NULL;
  self->target_addr = 0;
  self->type = SH_TASK_HOOK;
  self->typed.hook.new_addr = new_addr;
  self->typed.hook.orig_addr = orig_addr;
  self->typed.hook.flags = (size_t)flags;
  self->typed.hook.hooked = hooked;
  self->typed.hook.hooked_arg = hooked_arg;
  self->caller_addr = caller_addr;
  self->is_by_target_addr = false;
  self->is_sym_addr = true;
  self->is_proc_start = true;
  self->is_finished = false;
  self->is_corrupted = false;
  sh_ref_init(&self->ref);
  return self;

err:
  sh_task_destroy(self);
  return NULL;
}

sh_task_t *sh_task_create_intercept_by_target_addr(uintptr_t target_addr, shadowhook_interceptor_t pre,
                                                   void *data, uint32_t flags, bool is_sym_addr,
                                                   bool is_proc_start, uintptr_t caller_addr,
                                                   char *record_lib_name, char *record_sym_name) {
  sh_task_t *self = calloc(1, sizeof(sh_task_t));
  if (NULL == self) return NULL;
  self->lib_name = NULL;
  self->sym_name = NULL;
  if (NULL != record_lib_name) {
    if (NULL == (self->record_lib_name = strdup(record_lib_name))) goto err;
  } else {
    self->record_lib_name = NULL;
  }
  if (NULL != record_sym_name) {
    if (NULL == (self->record_sym_name = strdup(record_sym_name))) goto err;
  } else {
    self->record_sym_name = NULL;
  }
  self->target_addr = target_addr;
  self->type = SH_TASK_INTERCEPT;
  self->typed.intercept.pre = pre;
  self->typed.intercept.data = data;
  self->typed.intercept.flags = (size_t)flags;
  self->typed.intercept.intercepted = NULL;
  self->typed.intercept.intercepted_arg = NULL;
  self->caller_addr = caller_addr;
  self->is_by_target_addr = true;
  self->is_sym_addr = is_sym_addr;
  self->is_proc_start = is_proc_start;
  self->is_finished = false;
  self->is_corrupted = false;
  sh_ref_init(&self->ref);
  return self;

err:
  sh_task_destroy(self);
  return NULL;
}

sh_task_t *sh_task_create_intercept_by_sym_name(const char *lib_name, const char *sym_name,
                                                shadowhook_interceptor_t pre, void *data, uint32_t flags,
                                                shadowhook_intercepted_t intercepted, void *intercepted_arg,
                                                uintptr_t caller_addr) {
  sh_task_t *self = calloc(1, sizeof(sh_task_t));
  if (NULL == self) return NULL;
  if (NULL == (self->lib_name = strdup(lib_name))) goto err;
  if (NULL == (self->sym_name = strdup(sym_name))) goto err;
  self->record_lib_name = NULL;
  self->record_sym_name = NULL;
  self->target_addr = 0;
  self->type = SH_TASK_INTERCEPT;
  self->typed.intercept.pre = pre;
  self->typed.intercept.data = data;
  self->typed.intercept.flags = (size_t)flags;
  self->typed.intercept.intercepted = intercepted;
  self->typed.intercept.intercepted_arg = intercepted_arg;
  self->caller_addr = caller_addr;
  self->is_by_target_addr = false;
  self->is_sym_addr = true;
  self->is_proc_start = true;
  self->is_finished = false;
  self->is_corrupted = false;
  sh_ref_init(&self->ref);
  return self;

err:
  sh_task_destroy(self);
  return NULL;
}

void sh_task_destroy(sh_task_t *self) {
  SH_LOG_INFO("task: delayed destroy, target_addr %" PRIxPTR, self->target_addr);
  if (NULL != self->lib_name) free(self->lib_name);
  if (NULL != self->sym_name) free(self->sym_name);
  if (NULL != self->record_lib_name) free(self->record_lib_name);
  if (NULL != self->record_sym_name) free(self->record_sym_name);
  sh_ref_uninit(&self->ref);
  free(self);
}

static void sh_task_destroy_by_ref(sh_ref_t *ref) {
  sh_task_t *self = SH_UTIL_CONTAINER_OF(ref, sh_task_t, ref);
  sh_task_destroy(self);
}

static void sh_task_delayed_destroy(sh_task_t *self) {
  SH_LOG_INFO("task: destroy, target_addr %" PRIxPTR, self->target_addr);
  static sh_ref_mgr_t mgr = SH_REF_MGR_INITIALIZER(mgr, sh_task_destroy_by_ref, 0);
  sh_ref_delayed_destroy(&self->ref, &mgr);
}

static void sh_task_do_callback(sh_task_t *self, int error_number) {
  if (SH_TASK_HOOK == self->type && NULL != self->typed.hook.hooked)
    self->typed.hook.hooked(error_number, self->lib_name, self->sym_name, (void *)self->target_addr,
                            (void *)self->typed.hook.new_addr, self->typed.hook.orig_addr,
                            self->typed.hook.hooked_arg);
  else if (SH_TASK_INTERCEPT == self->type && NULL != self->typed.intercept.intercepted)
    self->typed.intercept.intercepted(error_number, self->lib_name, self->sym_name, (void *)self->target_addr,
                                      self->typed.intercept.pre, self->typed.intercept.data,
                                      self->typed.intercept.intercepted_arg);
}

static void sh_task_do_pending(sh_task_t *task, struct dl_phdr_info *info, void **cached_handle) {
  sh_ref_lock(&task->ref);

  // task destroyed?
  if (sh_ref_is_destroyed(&task->ref)) goto end;

  // ELF not found?
  sh_addr_info_t addr_info;
  memset(&addr_info, 0, sizeof(sh_addr_info_t));
  int r = sh_linker_get_addr_info_by_handle(&addr_info, cached_handle, info, task->sym_name);

  // do hook or intercept
  sh_recorder_trace_t trace = SH_RECORDER_TRACE_INITIALIZER;
  if (0 == r) {
    task->target_addr = (uintptr_t)addr_info.dli_saddr;
    if (SH_TASK_HOOK == task->type)
      r = sh_switch_hook(task->target_addr, &addr_info, task->typed.hook.new_addr, task->typed.hook.orig_addr,
                         task->typed.hook.flags, &trace);
    else
      r = sh_switch_intercept(task->target_addr, &addr_info, task->typed.intercept.pre,
                              task->typed.intercept.data, task->typed.intercept.flags, &trace);
    if (0 != r) task->is_corrupted = true;
  } else {
    task->is_corrupted = true;
  }
  sh_linker_free_addr_info(&addr_info);

  // do record
  uint8_t op;
  uintptr_t new_addr;
  uint32_t flags;
  if (SH_TASK_HOOK == task->type) {
    op = SH_RECORDER_OP_HOOK_SYM_NAME;
    new_addr = task->typed.hook.new_addr;
    flags = (uint32_t)task->typed.hook.flags;
  } else {
    op = SH_RECORDER_OP_INTERCEPT_SYM_NAME;
    new_addr = (uintptr_t)task->typed.intercept.pre;
    flags = (uint32_t)task->typed.intercept.flags;
  }
  sh_recorder_add_op(r, op, task->target_addr, task->lib_name, task->sym_name, new_addr, flags,
                     (uintptr_t)task, task->caller_addr, NULL, &trace);

  task->is_finished = true;
  sh_task_do_callback(task, r);
  __atomic_fetch_sub(&sh_tasks_unfinished_cnt, 1, __ATOMIC_RELEASE);

end:
  sh_ref_unlock(&task->ref);
  sh_ref_decrement_count(&task->ref);
}

static int sh_task_try_pending(struct dl_phdr_info *info, size_t size, void *arg) {
  (void)size, (void)arg;
  sh_task_queue_t matched_pending_tasks = TAILQ_HEAD_INITIALIZER(matched_pending_tasks);

  pthread_mutex_lock(&sh_tasks_lock);
  sh_task_t *task;
  TAILQ_FOREACH(task, &sh_tasks, link) {
    if (!task->is_finished && !task->is_corrupted && !task->is_by_target_addr &&
        sh_util_match_pathname(info->dlpi_name, task->lib_name)) {
      TAILQ_INSERT_TAIL(&matched_pending_tasks, task, link_tmp);
      sh_ref_increment_count(&task->ref);
    }
  }
  pthread_mutex_unlock(&sh_tasks_lock);

  void *cached_handle = NULL;
  while (NULL != (task = TAILQ_FIRST(&matched_pending_tasks))) {
    TAILQ_REMOVE(&matched_pending_tasks, task, link_tmp);
    sh_task_do_pending(task, info, &cached_handle);
  }
  if (NULL != cached_handle) sh_linker_close_handle(cached_handle);

  return __atomic_load_n(&sh_tasks_unfinished_cnt, __ATOMIC_ACQUIRE) > 0 ? 0 : 1;
}

#ifdef SH_CONFIG_COMPATIBLE_WITH_ARM_ANDROID_4_X
static void sh_task_dlopen_post(void) {
  SH_LOG_INFO("task: dlopen() post callback");
  if (__atomic_load_n(&sh_tasks_unfinished_cnt, __ATOMIC_ACQUIRE) > 0) {
    xdl_iterate_phdr(sh_task_try_pending, NULL, XDL_DEFAULT);
  }
}
#endif

static void sh_task_dl_init_pre(struct dl_phdr_info *info, size_t size, void *data) {
  SH_LOG_INFO("task: call_ctors() pre callback. load_bias %" PRIxPTR ", name %s", (uintptr_t)info->dlpi_addr,
              info->dlpi_name);
  if (__atomic_load_n(&sh_tasks_unfinished_cnt, __ATOMIC_ACQUIRE) > 0) {
    sh_task_try_pending(info, size, data);
  }
}

static void sh_task_dl_fini_post(struct dl_phdr_info *info, size_t size, void *data) {
  (void)size, (void)data;

  SH_LOG_INFO("task: call_dtors() post callback. load_bias %" PRIxPTR ", name %s", (uintptr_t)info->dlpi_addr,
              info->dlpi_name);

  // free switch(es) that are no longer needed (for the currently dlclosed ELF)
  sh_switch_free_after_dlclose(info);

  // reset "finished flag" for finished-task (for the currently dlclosed ELF)
  sh_task_queue_t matched_finished_tasks = TAILQ_HEAD_INITIALIZER(matched_finished_tasks);
  pthread_mutex_lock(&sh_tasks_lock);
  sh_task_t *task;
  TAILQ_FOREACH(task, &sh_tasks, link) {
    if (task->is_finished && !task->is_by_target_addr && 0 != task->target_addr &&
        sh_linker_is_addr_in_elf_pt_load(task->target_addr, (void *)info->dlpi_addr, info->dlpi_phdr,
                                         info->dlpi_phnum)) {
      TAILQ_INSERT_TAIL(&matched_finished_tasks, task, link_tmp);
      sh_ref_increment_count(&task->ref);
    }
  }
  pthread_mutex_unlock(&sh_tasks_lock);

  while (NULL != (task = TAILQ_FIRST(&matched_finished_tasks))) {
    TAILQ_REMOVE(&matched_finished_tasks, task, link_tmp);
    sh_ref_lock(&task->ref);
    if (!sh_ref_is_destroyed(&task->ref)) {
      task->target_addr = 0;
      task->is_finished = false;
      task->is_corrupted = false;
      __atomic_fetch_add(&sh_tasks_unfinished_cnt, 1, __ATOMIC_RELEASE);
      SH_LOG_INFO("task: reset finished flag for: %s lib_name %s, sym_name %s",
                  SH_TASK_HOOK == task->type ? "hook" : "intercept", task->lib_name, task->sym_name);
    }
    sh_ref_unlock(&task->ref);
    sh_ref_decrement_count(&task->ref);
  }
}

int sh_task_init(void) {
#ifdef SH_CONFIG_COMPATIBLE_WITH_ARM_ANDROID_4_X
  if (__predict_false(sh_util_get_api_level() < __ANDROID_API_L__)) {
    SH_LOG_INFO("task: start linker dlopen monitor ...");
    if (0 != sh_linker_register_dlopen_post_callback(sh_task_dlopen_post)) return -1;
    return 0;
  }
#endif

  SH_LOG_INFO("task: start linker DL-init/fini monitor ...");
  if (0 != sh_linker_register_dl_init_callback(sh_task_dl_init_pre, NULL, NULL)) return -1;
  if (0 != sh_linker_register_dl_fini_callback(NULL, sh_task_dl_fini_post, NULL)) return -1;
  return 0;
}

int sh_task_do(sh_task_t *self) {
  int r;
  sh_recorder_trace_t trace = SH_RECORDER_TRACE_INITIALIZER;
  sh_addr_info_t addr_info;
  memset(&addr_info, 0, sizeof(sh_addr_info_t));

  // find target-address by library-name and symbol-name
  if (!self->is_by_target_addr) {
    if (0 != (r = sh_linker_get_addr_info_by_name(&addr_info, self->lib_name, self->sym_name))) goto end;
    self->target_addr = (uintptr_t)addr_info.dli_saddr;
  } else {
    addr_info.is_sym_addr = self->is_sym_addr;
    addr_info.is_proc_start = self->is_proc_start;
  }

  // hook or intercept by target-address
  if (SH_TASK_HOOK == self->type)
    r = sh_switch_hook(self->target_addr, &addr_info, self->typed.hook.new_addr, self->typed.hook.orig_addr,
                       self->typed.hook.flags, &trace);
  else
    r = sh_switch_intercept(self->target_addr, &addr_info, self->typed.intercept.pre,
                            self->typed.intercept.data, self->typed.intercept.flags, &trace);
  self->is_finished = true;

end:
  if (0 == r || SHADOWHOOK_ERRNO_PENDING == r /* "PENDING" is NOT an error */) {
    pthread_mutex_lock(&sh_tasks_lock);
    TAILQ_INSERT_TAIL(&sh_tasks, self, link);
    if (!self->is_finished) __atomic_fetch_add(&sh_tasks_unfinished_cnt, 1, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&sh_tasks_lock);
  }
  sh_linker_free_addr_info(&addr_info);

  // record
  uint8_t op;
  uintptr_t new_addr;
  uint32_t flags;
  if (SH_TASK_HOOK == self->type) {
    new_addr = self->typed.hook.new_addr;
    flags = (uint32_t)self->typed.hook.flags;
    if (!self->is_by_target_addr) {
      op = SH_RECORDER_OP_HOOK_SYM_NAME;
    } else {
      if (!self->is_proc_start) {
        op = SH_RECORDER_OP_HOOK_INSTR_ADDR;
      } else {
        if (!self->is_sym_addr) {
          op = SH_RECORDER_OP_HOOK_FUNC_ADDR;
        } else {
          op = SH_RECORDER_OP_HOOK_SYM_ADDR;
        }
      }
    }
  } else {
    new_addr = (uintptr_t)self->typed.intercept.pre;
    flags = (uint32_t)self->typed.intercept.flags;
    if (!self->is_by_target_addr) {
      op = SH_RECORDER_OP_INTERCEPT_SYM_NAME;
    } else {
      if (!self->is_proc_start) {
        op = SH_RECORDER_OP_INTERCEPT_INSTR_ADDR;
      } else {
        if (!self->is_sym_addr) {
          op = SH_RECORDER_OP_INTERCEPT_FUNC_ADDR;
        } else {
          op = SH_RECORDER_OP_INTERCEPT_SYM_ADDR;
        }
      }
    }
  }
  char *lib_name = self->lib_name;
  if (NULL == lib_name) lib_name = self->record_lib_name;
  if (NULL == lib_name) lib_name = "unknown";
  char *sym_name = self->sym_name;
  if (NULL == sym_name) sym_name = self->record_sym_name;
  if (NULL == sym_name) sym_name = "unknown";
  sh_recorder_add_op(r, op, self->target_addr, lib_name, sym_name, new_addr, flags, (uintptr_t)self,
                     self->caller_addr, NULL, &trace);
  return r;
}

int sh_task_undo_and_destroy(sh_task_t *self, uintptr_t caller_addr) {
  pthread_mutex_lock(&sh_tasks_lock);
  TAILQ_REMOVE(&sh_tasks, self, link);
  sh_ref_increment_count(&self->ref);
  pthread_mutex_unlock(&sh_tasks_lock);

  sh_ref_lock(&self->ref);

  sh_recorder_trace_t trace = SH_RECORDER_TRACE_INITIALIZER;

  // check task status
  int r;
  if (sh_ref_is_destroyed(&self->ref)) {
    r = 0;
    goto end;
  }
  if (!self->is_finished) {
    __atomic_fetch_sub(&sh_tasks_unfinished_cnt, 1, __ATOMIC_RELEASE);
    r = SHADOWHOOK_ERRNO_UNHOOK_ON_UNFINISHED;
    goto end;
  }
  if (self->is_corrupted) {
    r = SHADOWHOOK_ERRNO_UNHOOK_ON_ERROR;
    goto end;
  }

  // do unhook or unintercept
  if (SH_TASK_HOOK == self->type)
    r = sh_switch_unhook(self->target_addr, self->typed.hook.new_addr, self->typed.hook.flags, &trace);
  else
    r = sh_switch_unintercept(self->target_addr, self->typed.intercept.pre, self->typed.intercept.data,
                              &trace);

end:
  sh_recorder_add_unop(r, SH_TASK_HOOK == self->type ? SH_RECORDER_OP_UNHOOK : SH_RECORDER_OP_UNINTERCEPT,
                       (uintptr_t)self, caller_addr, NULL, &trace);
  sh_task_delayed_destroy(self);
  sh_ref_unlock(&self->ref);
  sh_ref_decrement_count(&self->ref);
  return r;
}
