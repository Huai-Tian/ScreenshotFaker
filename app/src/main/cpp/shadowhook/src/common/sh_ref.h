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

// Created by Kelun Cai (caikelun@bytedance.com) on 2025-11-13.

#pragma once
#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <time.h>

#include "queue.h"

typedef struct sh_ref {
  pthread_mutex_t lock;
  int count;
  uint32_t destroy_ts;
  TAILQ_ENTRY(sh_ref, ) link;
} sh_ref_t;

typedef TAILQ_HEAD(sh_ref_queue, sh_ref, ) sh_ref_queue_t;

void sh_ref_init(sh_ref_t *self);
void sh_ref_uninit(sh_ref_t *self);

void sh_ref_lock(sh_ref_t *self);
void sh_ref_unlock(sh_ref_t *self);

void sh_ref_increment_count(sh_ref_t *self);
void sh_ref_decrement_count(sh_ref_t *self);

bool sh_ref_is_destroyed(sh_ref_t *self);

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wpadded"
typedef struct {
  sh_ref_queue_t queue;
  pthread_mutex_t lock;
  void (*destroy)(sh_ref_t *);
  uint32_t delay_sec;
} sh_ref_mgr_t;
#pragma clang diagnostic pop

#define SH_REF_MGR_INITIALIZER(self, destroy, delay_sec) \
  {TAILQ_HEAD_INITIALIZER((self).queue), PTHREAD_MUTEX_INITIALIZER, (destroy), (delay_sec)}

void sh_ref_delayed_destroy(sh_ref_t *self, sh_ref_mgr_t *mgr);
