#include "cad/kernel/error.h"

namespace cad::kernel {

KernelError::KernelError(ErrorCode code, const std::string& message)
    : std::runtime_error(message), code_(code) {}

}  // namespace cad::kernel
