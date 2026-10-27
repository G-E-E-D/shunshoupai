#include "connection.h"

#include <algorithm>
#include <array>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace shunshoupai {
namespace {

[[noreturn]] void invalid() {
    throw std::invalid_argument("Invalid HTTP(S) service address or pairing QR code.");
}

bool asciiSpace(unsigned char c) { return c == ' ' || (c >= 9 && c <= 13); }
bool digit(char c) { return c >= '0' && c <= '9'; }
bool alpha(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }
std::string lower(std::string value) {
    for (char& c : value) if (c >= 'A' && c <= 'Z') c = static_cast<char>(c + ('a' - 'A'));
    return value;
}

std::vector<std::string> split(const std::string& value, char separator) {
    std::vector<std::string> result;
    std::size_t begin = 0;
    for (;;) {
        const auto end = value.find(separator, begin);
        result.push_back(value.substr(begin, end == std::string::npos ? end : end - begin));
        if (end == std::string::npos) return result;
        begin = end + 1;
    }
}

std::array<std::uint16_t, 2> ipv4Words(const std::string& host) {
    const auto parts = split(host, '.');
    if (parts.size() != 4) invalid();
    std::array<unsigned, 4> octets{};
    for (std::size_t i = 0; i < parts.size(); ++i) {
        if (parts[i].empty() || parts[i].size() > 3 || (parts[i].size() > 1 && parts[i][0] == '0')) invalid();
        for (char c : parts[i]) {
            if (!digit(c)) invalid();
            octets[i] = octets[i] * 10 + static_cast<unsigned>(c - '0');
        }
        if (octets[i] > 255) invalid();
    }
    return {static_cast<std::uint16_t>((octets[0] << 8) | octets[1]),
            static_cast<std::uint16_t>((octets[2] << 8) | octets[3])};
}

std::vector<std::uint16_t> ipv6Part(const std::string& value, bool allowIpv4) {
    std::vector<std::uint16_t> result;
    if (value.empty()) return result;
    const auto parts = split(value, ':');
    for (std::size_t i = 0; i < parts.size(); ++i) {
        const auto& part = parts[i];
        if (part.find('.') != std::string::npos) {
            if (!allowIpv4 || i + 1 != parts.size()) invalid();
            const auto words = ipv4Words(part);
            result.insert(result.end(), words.begin(), words.end());
        } else {
            if (part.empty() || part.size() > 4) invalid();
            std::uint16_t word = 0;
            for (char c : lower(part)) {
                if (!digit(c) && !(c >= 'a' && c <= 'f')) invalid();
                word = static_cast<std::uint16_t>((word << 4) | (digit(c) ? c - '0' : c - 'a' + 10));
            }
            result.push_back(word);
        }
    }
    return result;
}

std::string ipv6Host(const std::string& value) {
    const auto compression = value.find("::");
    std::vector<std::uint16_t> words;
    if (compression == std::string::npos) {
        words = ipv6Part(value, true);
        if (words.size() != 8) invalid();
    } else {
        if (value.find("::", compression + 2) != std::string::npos) invalid();
        auto left = ipv6Part(value.substr(0, compression), false);
        auto right = ipv6Part(value.substr(compression + 2), true);
        if (left.size() + right.size() >= 8) invalid();
        words = left;
        words.insert(words.end(), 8 - left.size() - right.size(), 0);
        words.insert(words.end(), right.begin(), right.end());
    }
    std::size_t bestStart = 8, bestCount = 0;
    for (std::size_t i = 0; i < 8;) {
        if (words[i] != 0) { ++i; continue; }
        const auto start = i;
        while (i < 8 && words[i] == 0) ++i;
        if (i - start > bestCount && i - start >= 2) { bestStart = start; bestCount = i - start; }
    }
    const char* hex = "0123456789abcdef";
    std::string host;
    for (std::size_t i = 0; i < 8;) {
        if (i == bestStart) { host += "::"; i += bestCount; continue; }
        if (!host.empty() && host.back() != ':') host += ':';
        bool started = false;
        for (int shift = 12; shift >= 0; shift -= 4) {
            const unsigned nibble = (words[i] >> shift) & 15;
            if (nibble || started || shift == 0) { host += hex[nibble]; started = true; }
        }
        ++i;
    }
    return '[' + host + ']';
}

std::string dnsHost(const std::string& input) {
    std::string host = lower(input);
    if (host.empty() || host.size() > 253) invalid();
    const bool trailingDot = host.back() == '.';
    const std::string bare = trailingDot ? host.substr(0, host.size() - 1) : host;
    if (bare.empty()) invalid();
    // WHATWG browsers interpret shortened/octal/hex/numeric hosts as IPv4.
    // Accept only unambiguous four-octet decimal IPv4 for numeric final labels.
    const auto labels = split(bare, '.');
    const auto& last = labels.back();
    if (last.empty()) invalid();
    const bool numericLast = std::all_of(last.begin(), last.end(), digit)
        || (last.size() > 2 && last[0] == '0' && last[1] == 'x');
    if (numericLast) {
        if (trailingDot) invalid();
        ipv4Words(host);
        return host;
    }
    for (const auto& label : labels) {
        if (label.empty() || label.size() > 63 || label.front() == '-' || label.back() == '-') invalid();
        for (char c : label) if (!alpha(c) && !digit(c) && c != '-') invalid();
    }
    return host;
}

struct Parsed {
    std::string origin;
    std::string suffix;
};

Parsed parseUrl(const std::string& raw) {
    if (raw.size() > 2048) invalid();
    std::size_t start = 0, end = raw.size();
    while (start < end && asciiSpace(static_cast<unsigned char>(raw[start]))) ++start;
    while (end > start && asciiSpace(static_cast<unsigned char>(raw[end - 1]))) --end;
    const std::string value = raw.substr(start, end - start);
    for (unsigned char c : value) if (c <= 32 || c >= 127 || c == '\\') invalid();
    const auto schemeEnd = value.find("://");
    if (schemeEnd == std::string::npos) invalid();
    const std::string scheme = lower(value.substr(0, schemeEnd));
    if (scheme != "http" && scheme != "https") invalid();
    const auto authorityBegin = schemeEnd + 3;
    auto authorityEnd = value.find_first_of("/?#", authorityBegin);
    if (authorityEnd == std::string::npos) authorityEnd = value.size();
    const std::string authority = value.substr(authorityBegin, authorityEnd - authorityBegin);
    if (authority.empty() || authority.find_first_of("@%") != std::string::npos) invalid();
    std::string host, portText;
    if (authority.front() == '[') {
        const auto close = authority.find(']');
        if (close == std::string::npos) invalid();
        host = ipv6Host(authority.substr(1, close - 1));
        if (close + 1 < authority.size()) {
            if (authority[close + 1] != ':') invalid();
            portText = authority.substr(close + 2);
            if (portText.empty()) invalid();
        }
    } else {
        const auto colon = authority.find(':');
        host = dnsHost(authority.substr(0, colon));
        if (colon != std::string::npos) {
            portText = authority.substr(colon + 1);
            if (portText.empty()) invalid();
        }
    }
    unsigned port = 0;
    if (!portText.empty()) {
        if (portText.size() > 5) invalid();
        for (char c : portText) { if (!digit(c)) invalid(); port = port * 10 + static_cast<unsigned>(c - '0'); }
        if (port == 0 || port > 65535) invalid();
    }
    const bool defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443);
    return {scheme + "://" + host + (port && !defaultPort ? ":" + std::to_string(port) : "") + "/",
            value.substr(authorityEnd)};
}

}  // namespace

Connection parseConnection(const std::string& value, bool allowPairing) {
    const auto parsed = parseUrl(value);
    if (parsed.suffix.empty() || parsed.suffix == "/") return {parsed.origin, ""};
    if (!allowPairing) invalid();
    const std::string prefix = "/#pair=";
    // Also accept an omitted slash after the authority; canonical output has it.
    std::string token;
    if (parsed.suffix.compare(0, prefix.size(), prefix) == 0) token = parsed.suffix.substr(prefix.size());
    else if (parsed.suffix.compare(0, 6, "#pair=") == 0) token = parsed.suffix.substr(6);
    else invalid();
    if (token.size() != 43) invalid();
    for (char c : token) if (!alpha(c) && !digit(c) && c != '_' && c != '-') invalid();
    return {parsed.origin, token};
}

bool sameOrigin(const std::string& service, const std::string& destination) noexcept {
    try { return parseConnection(service, false).origin == parseUrl(destination).origin; }
    catch (...) { return false; }
}

}  // namespace shunshoupai
