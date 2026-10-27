#include "connection.h"

#include <iostream>
#include <stdexcept>
#include <string>

namespace {
unsigned assertions = 0;
void require(bool condition, const std::string& message) {
    ++assertions;
    if (!condition) throw std::runtime_error(message);
}
void rejects(const std::string& value, bool pairing = true) {
    try { shunshoupai::parseConnection(value, pairing); }
    catch (const std::invalid_argument&) { ++assertions; return; }
    throw std::runtime_error("Unexpectedly accepted unsafe URL");
}
}

int main() {
    using shunshoupai::parseConnection;
    using shunshoupai::sameOrigin;
    try {
        require(parseConnection(" HTTPS://EXAMPLE.com:443/ ", false).origin == "https://example.com/", "HTTPS normalization");
        require(parseConnection("http://localhost:03000", false).origin == "http://localhost:3000/", "Port normalization");
        require(parseConnection("http://192.168.1.20:3000", false).origin == "http://192.168.1.20:3000/", "LAN root");
        require(parseConnection("http://[0:0:0:0:0:0:0:1]:80/", false).origin == "http://[::1]/", "IPv6 canonical form");
        require(parseConnection("http://[::ffff:127.0.0.1]/", false).origin == "http://[::ffff:7f00:1]/", "IPv4-mapped IPv6");
        const std::string token(43, 'A');
        const auto paired = parseConnection("http://192.168.1.20:3000/#pair=" + token, true);
        require(paired.origin == "http://192.168.1.20:3000/" && paired.pairingToken == token, "Separate root and ephemeral pairing token");
        require(parseConnection("https://example.com", true).pairingToken.empty(), "Plain connection QR remains available");
        rejects("https://example.com/#pair=" + token, false);
        for (const char* value : {
            "", "javascript:alert(1)", "file:///private", "https://user:password@example.com", "https://example.com?token=x",
            "https://example.com/#session=x", "https://example.com/nested", "https://example.com:", "https://example.com:0",
            "https://example.com:65536", "https://example.com:-1", "https://example.com:1:2", "https://example.com\\@evil.invalid",
            "https://example.com%2f.evil.invalid", "https://example..com", "https://-example.com", "https://example.com\n.evil.invalid",
            "http://127.1", "http://2130706433", "http://0177.0.0.1", "http://0x7f000001", "http://256.0.0.1", "http://[:::1]",
            "http://[1:2:3:4:5:6:7]", "http://[::1%25lo0]", "https://example.com/#pair=short"
        }) rejects(value);
        rejects("https://example.com/#pair=" + token + "&other=1");
        rejects("https://example.com/?q=1#pair=" + token);
        rejects("https://example.com/path#pair=" + token);
        rejects("https://example.com/#pair=" + token + "#other");
        require(sameOrigin("https://example.com/", "https://EXAMPLE.com:443/api/events?q=1#fragment"), "WebView in-origin navigation");
        require(sameOrigin("http://[::1]/", "http://[0:0:0:0:0:0:0:1]:80/api"), "Canonical IPv6 origins");
        for (const char* destination : {
            "http://example.com/", "https://example.com:444/", "https://example.com.evil.invalid/",
            "https://example.com@evil.invalid/", "https://example.com\\@evil.invalid/", "javascript:alert(1)",
            "file:///private", "https://example.com:99999/", "//example.com/api"
        }) require(!sameOrigin("https://example.com/", destination), "Origin escape must fail closed");
        std::cout << "PASS " << assertions << " shared C++ connection assertions\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
