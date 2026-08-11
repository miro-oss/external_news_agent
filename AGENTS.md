# Repository Rules

## Branch Naming

Use this format:

- `feat/#issue-number`
- `fix/#issue-number`
- `refactor/#issue-number`
- `setting/#issue-number`
- `chore/#issue-number`
- `deploy/#issue-number`

Examples:

- `feat/#12`
- `setting/#3`

Do not create branches with `codex/`, `agent/`, or free-form names unless the user explicitly requests it.

## Commit Convention

Allowed types:

- `feat`
- `refactor`
- `fix`
- `setting`
- `chore`
- `deploy`

Commit title format:

```text
[TYPE/#issue] title
```

Examples:

```text
[SETTING/#1] 로컬 풀스택 개발 계획 및 CI 정비
[FEAT/#12] 뉴스 수집 설정 화면 추가
[FIX/#15] 프론트엔드 CI pnpm 설치 오류 수정
```

Commit body is optional, but use it when the change needs context.

## Pull Request

PR title should follow the same style as the main commit when possible:

```text
[SETTING/#1] 로컬 풀스택 개발 계획 및 CI 정비
```

PR body must include:

```text
Close #issue-number
```

## Codex Instructions

When creating a branch, commit, or PR, always ask for or infer the issue number first. If no issue exists, create the issue before creating the branch.

## Secrets and Environment Files

- Do not open, read, print, summarize, or quote real secret files such as `.env`, `.env.*`, `application-secret.*`, or credential JSON files.
- Use `.env.example`, application config files without secrets, or user-provided key names when environment variable names are needed.
- Never commit API keys, tokens, passwords, client secrets, private keys, or full authorization headers.
- If a command accidentally prints a secret, do not repeat the value in chat or documentation. Stop using that output and ask the user to rotate the exposed credential if needed.
