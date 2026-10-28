#pragma once

#include <string>

namespace shunshoupai {

struct Connection {
    // Canonical HTTP(S) root, always ending in '/'. Safe to remember.
    std::string origin;
    // Short-lived device-pairing capability. Never persist this field.
    std::string pairingToken;
};

// Accept a service root, or (only when allowed) root/#pair=<43 base64url>.
// Throws std::invalid_argument for untrusted or ambiguous URL syntax.
Connection parseConnection(const std::string& value, bool allowPairing);

// Destinations may contain a path/query/fragment; their origin must match the
// validated service root. Invalid syntax always fails closed.
bool sameOrigin(const std::string& service, const std::string& destination) noexcept;

}  // namespace shunshoupai
