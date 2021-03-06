# Copyright 2012 Cloudera Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


# where to put generated libraries
set(LIBRARY_OUTPUT_PATH "${BUILD_OUTPUT_ROOT_DIRECTORY}/runtime")

# where to put generated binaries
set(EXECUTABLE_OUTPUT_PATH "${BUILD_OUTPUT_ROOT_DIRECTORY}/runtime")

add_library(Runtime STATIC
  client-cache.cc
  coordinator.cc
  data-stream-mgr.cc
  data-stream-sender.cc
  descriptors.cc
  disk-io-mgr.cc
  disk-io-mgr-reader-context.cc
  disk-io-mgr-scan-range.cc
  disk-io-mgr-stress.cc
  exec-env.cc
  hbase-table.cc
  hbase-table-factory.cc
  hdfs-fs-cache.cc
  lib-cache.cc
  mem-tracker.cc
  mem-pool.cc
  parallel-executor.cc
  plan-fragment-executor.cc
  primitive-type.cc
  raw-value.cc
  raw-value-test.cc
  row-batch.cc
  runtime-state.cc
  string-value.cc
  thread-resource-mgr.cc
  timestamp-parse-util.cc
  timestamp-value.cc
  tuple.cc
  tuple-row.cc
)

# This test runs forever so should not be part of 'make test'
add_executable(disk-io-mgr-stress-test disk-io-mgr-stress-test.cc)
target_link_libraries(disk-io-mgr-stress-test ${IMPALA_TEST_LINK_LIBS})

ADD_BE_TEST(mem-pool-test)
ADD_BE_TEST(free-pool-test)
ADD_BE_TEST(string-buffer-test)
ADD_BE_TEST(data-stream-test)
ADD_BE_TEST(timestamp-test)
ADD_BE_TEST(disk-io-mgr-test)
ADD_BE_TEST(parallel-executor-test)
ADD_BE_TEST(raw-value-test)
ADD_BE_TEST(string-value-test)
ADD_BE_TEST(thread-resource-mgr-test)
ADD_BE_TEST(mem-tracker-test)
ADD_BE_TEST(multi-precision-test)
