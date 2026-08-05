## Summary

## Diagnostics or UX behavior changed

## Safety review

- [ ] Scanning remains non-mutating.
- [ ] Mutating commands show exact arguments and require confirmation.
- [ ] No credentials or tokens are read, stored, or logged.
- [ ] Filesystem/process work stays off the EDT.

## Verification

- [ ] `gradle check`
- [ ] `gradle buildPlugin`
- [ ] `gradle verifyPlugin`
