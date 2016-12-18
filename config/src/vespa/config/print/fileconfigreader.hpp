// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/config/common/exceptions.h>
#include <fstream>
#include <sstream>

namespace config {

template <typename ConfigType>
FileConfigReader<ConfigType>::FileConfigReader(const vespalib::string & fileName)
    : _fileName(fileName)
{
}

template <typename ConfigType>
std::unique_ptr<ConfigType>
FileConfigReader<ConfigType>::read(const ConfigFormatter & formatter)
{
    ConfigDataBuffer buffer;
    std::ifstream file(_fileName.c_str());
    if (!file.is_open())
        throw ConfigReadException("error: unable to read file '%s'", _fileName.c_str());

    std::stringstream buf;
    buf << file.rdbuf();
    buffer.setEncodedString(buf.str());
    formatter.decode(buffer);
    return std::unique_ptr<ConfigType>(new ConfigType(buffer));
}

template <typename ConfigType>
std::unique_ptr<ConfigType>
FileConfigReader<ConfigType>::read()
{
    std::vector<vespalib::string> lines;
    std::ifstream f(_fileName.c_str());
    if (f.fail())
        throw vespalib::IllegalArgumentException(std::string("Unable to open file ") + _fileName);
    std::string line;
    while (getline(f, line)) {
        lines.push_back(line);
    }
    return std::unique_ptr<ConfigType>(new ConfigType(ConfigValue(lines, calculateContentMd5(lines))));
}

} // namespace config
