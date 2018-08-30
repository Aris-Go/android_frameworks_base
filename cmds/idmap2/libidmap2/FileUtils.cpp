/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <dirent.h>
#include <errno.h>
#include <sys/types.h>
#include <unistd.h>
#include <fstream>

#include "idmap2/FileUtils.h"

namespace android {
namespace idmap2 {
namespace utils {

std::unique_ptr<std::vector<std::string>> FindFiles(const std::string& root, bool recurse,
                                                    const FindFilesPredicate& predicate) {
  DIR* dir = opendir(root.c_str());
  if (!dir) {
    return nullptr;
  }
  std::unique_ptr<std::vector<std::string>> vector(new std::vector<std::string>());
  struct dirent* dirent;
  while ((dirent = readdir(dir))) {
    const std::string path = root + "/" + dirent->d_name;
    if (predicate(dirent->d_type, path)) {
      vector->push_back(path);
    }
    if (recurse && dirent->d_type == DT_DIR && strcmp(dirent->d_name, ".") &&
        strcmp(dirent->d_name, "..")) {
      auto sub_vector = FindFiles(path, recurse, predicate);
      if (!sub_vector) {
        return nullptr;
      }
      vector->insert(vector->end(), sub_vector->begin(), sub_vector->end());
    }
  }
  closedir(dir);

  return vector;
}

std::unique_ptr<std::string> ReadFile(const std::string& path) {
  std::unique_ptr<std::string> str(new std::string());
  std::ifstream fin(path);
  str->append({std::istreambuf_iterator<char>(fin), std::istreambuf_iterator<char>()});
  fin.close();
  return str;
}

std::unique_ptr<std::string> ReadFile(int fd) {
  std::unique_ptr<std::string> str(new std::string());
  char buf[1024];
  ssize_t r;
  while ((r = read(fd, buf, sizeof(buf))) > 0) {
    str->append(buf, r);
  }
  if (r != 0) {
    return nullptr;
  }
  return str;
}

}  // namespace utils
}  // namespace idmap2
}  // namespace android
