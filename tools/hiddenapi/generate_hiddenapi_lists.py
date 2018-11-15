#!/usr/bin/env python
#
# Copyright (C) 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""
Generate API lists for non-SDK API enforcement.

usage: generate-hiddenapi-lists.py [-h]
                                   --input-public INPUT_PUBLIC
                                   --input-private INPUT_PRIVATE
                                   [--input-whitelists [INPUT_WHITELISTS [INPUT_WHITELISTS ...]]]
                                   [--input-greylists [INPUT_GREYLISTS [INPUT_GREYLISTS ...]]]
                                   [--input-blacklists [INPUT_BLACKLISTS [INPUT_BLACKLISTS ...]]]
                                   --output-whitelist OUTPUT_WHITELIST
                                   --output-light-greylist OUTPUT_LIGHT_GREYLIST
                                   --output-dark-greylist OUTPUT_DARK_GREYLIST
                                   --output-blacklist OUTPUT_BLACKLIST
"""
import argparse
import os
import sys
import re

TAG_WHITELIST = "whitelist"
TAG_GREYLIST = "greylist"
TAG_BLACKLIST = "blacklist"
TAG_BLACKLIST_MAX_O = "blacklist-max-o"

TAG_IGNORE_CONFLICTS_SUFFIX = "-ignore-conflicts"

TAGS = [
    TAG_WHITELIST,
    TAG_GREYLIST,
    TAG_BLACKLIST,
    TAG_BLACKLIST_MAX_O,
]

SERIALIZATION_PATTERNS = [
    r'readObject\(Ljava/io/ObjectInputStream;\)V',
    r'readObjectNoData\(\)V',
    r'readResolve\(\)Ljava/lang/Object;',
    r'serialVersionUID:J',
    r'serialPersistentFields:\[Ljava/io/ObjectStreamField;',
    r'writeObject\(Ljava/io/ObjectOutputStream;\)V',
    r'writeReplace\(\)Ljava/lang/Object;',
]

SERIALIZATION_REGEX = re.compile(r'.*->(' + '|'.join(SERIALIZATION_PATTERNS) + r')$')

def get_args():
    """Parses command line arguments.

    Returns:
        Namespace: dictionary of parsed arguments
    """
    parser = argparse.ArgumentParser()
    parser.add_argument('--public', required=True, help='List of all public members')
    parser.add_argument('--private', required=True, help='List of all private members')

    parser.add_argument('--flags', nargs='*', default=[],
            help='CSV files to include in the flags')

    for tag in TAGS:
        ignore_conflicts_tag = tag + TAG_IGNORE_CONFLICTS_SUFFIX
        parser.add_argument('--' + tag, dest=tag, nargs='*', default=[],
            help='Lists of members tag ' + tag)
        parser.add_argument('--' + ignore_conflicts_tag, dest=ignore_conflicts_tag, nargs='*',
            default=[], help='Lists of members tag ' + tag)
    parser.add_argument('--output', required=True)
    return parser.parse_args()

def read_lines(filename):
    """Reads entire file and return it as a list of lines.

    Lines which begin with a hash are ignored.

    Args:
        filename (string): Path to the file to read from.

    Returns:
        list: Lines of the loaded file as a list of strings.
    """
    with open(filename, 'r') as f:
        return map(lambda line: line.strip(),
                   filter(lambda line: not line.startswith('#'),f.readlines()))

def write_lines(filename, lines):
    """Writes list of lines into a file, overwriting the file it it exists.

    Args:
        filename (string): Path to the file to be writting into.
        lines (list): List of strings to write into the file.
    """
    with open(filename, 'w') as f:
        f.writelines(lines)

def check_input_set(api_subset, output, source):
    key_set = set(output.keys())
    assert key_set.issuperset(api_subset), (
        "Error processing: {}\n"
        "The following entries were not found:\n"
        "{}"
        "Please visit go/hiddenapi for more information.").format(
            source, "".join(map(lambda x: "  " + str(x), set(api_subset).difference(key_set))))

def check_tag_set(tags, source):
    assert set(TAGS).issuperset(tags), (
        "Error processing: {}\n"
        "The following tags were not recognized: \n"
        "{}\n"
        "Please visit go/hiddenapi for more information.").format(
            source, "\n".join(set(tags).difference(TAGS)))

def filter_dict_keys(fn_lambda, output):
    return filter(lambda x: fn_lambda(x, output[x]), output)

def is_unassigned():
    return lambda api, api_tags: not api_tags

def is_serialization():
    return lambda api, api_tags: SERIALIZATION_REGEX.match(api)

def assign_tag(tag, api_subset, output, ignore_conflicts=False, source="<unknown>"):
    check_tag_set([ tag ], source)
    if ignore_conflicts:
        unassigned_keys = filter_dict_keys(is_unassigned(), output)
        api_subset = set(api_subset).intersection(unassigned_keys)
    else:
        check_input_set(api_subset, output, source)
    map(lambda api: output[api].add(tag), api_subset)

def merge_csv(csv_lines, output, source = "<unknown>"):
    csv_values = map(lambda line: line.split(','), csv_lines)
    check_input_set(map(lambda csv: csv[0], csv_values), output, source)
    tags = reduce(lambda x, y: set(x).union(y), map(lambda csv: csv[1:], csv_values), [])
    check_tag_set(tags, source)
    map(lambda csv : output[csv[0]].update(csv[1:]), csv_values)

def merge_two_disjoint_dicts(x, y):
    assert set(x.keys()).isdisjoint(set(y.keys()))
    z = x.copy()
    z.update(y)
    return z

def create_output_file(output):
    return sorted(map(lambda api: ",".join([api] + sorted(output[api])) + "\n", output))

def main(argv):
    args = vars(get_args())

    public = { x: set([TAG_WHITELIST]) for x in read_lines(args["public"]) }
    private = { x: set() for x in read_lines(args["private"]) }
    output = merge_two_disjoint_dicts(public, private)

    assign_tag(TAG_WHITELIST, filter_dict_keys(is_serialization(), output), output)

    for filename in args["flags"]:
        merge_csv(read_lines(filename), output, filename)

    for tag in TAGS:
        for filename in args[tag]:
            assign_tag(tag, read_lines(filename), output, False, filename)

    for tag in TAGS:
        for filename in args[tag + TAG_IGNORE_CONFLICTS_SUFFIX]:
            assign_tag(tag, read_lines(filename), output, True, filename)

    assign_tag(TAG_BLACKLIST, filter_dict_keys(is_unassigned(), output), output)

    write_lines(args["output"], create_output_file(output))

if __name__ == "__main__":
    main(sys.argv)
