#pragma once

#include <stdexcept>
#include <string>

namespace cad::kernel {

/// Stable error codes. Values are mirrored in Kotlin `KernelException`.
enum class ErrorCode : int {
    InvalidArgument = 1,
    OcctFailure = 2,
    InvalidHandle = 3,
    IoError = 4,
    Unknown = 99,
};

class KernelError : public std::runtime_error {
public:
    KernelError(ErrorCode code, const std::string& message);
    ErrorCode code() const noexcept { return code_; }

private:
    ErrorCode code_;
};

}  // namespace cad::kernel
