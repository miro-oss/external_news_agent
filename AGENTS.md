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

## GitHub Issue and PR Templates

- Before creating or updating any GitHub issue, always inspect `.github/ISSUE_TEMPLATE/` and use the matching template for the issue type.
- Before creating or updating any pull request, always inspect `.github/pull_request_template.md` and preserve its headings, checkbox sections, and required wording.
- Fill in every relevant template section concretely. Do not replace the template with a free-form summary.
- Keep `Close #issue-number` in the PR body according to the template and repository rule.
- If a required template is missing, state that explicitly before falling back to the naming and body rules in this file.

## Codex Instructions

When creating a branch, commit, or PR, always ask for or infer the issue number first. If no issue exists, create the issue before creating the branch.

## GitHub Issue Labels

- When creating an issue, inspect the repository's existing labels and choose the label that matches the work type.
- Never create a new label for an issue unless the user explicitly requests a new label.
- Prefer the existing feature label for feature work, such as the repository's `✨ feat` label, rather than assuming a plain `feat` label exists.

## API Specification Source of Truth

- Before implementing, changing, or reviewing any backend or frontend API work, always inspect the Notion page `API 명세서` and the matching endpoint page in the `외부 뉴스 크롤링 에이전트 API` database.
- Treat the Notion API specification as the source of truth for HTTP method, URI, request header, path variable, query string, request body, response body, success code, error code, and user-facing message.
- Start API work from `공통 응답 규격 · 에러 코드`, then read the specific domain pages such as `소스(sources)`, `주제(topics)`, `수집실행(runs)`, `기사(articles)`, `보고서(reports)`, `검색(search)`, or `알림(notifications)`.
- Match Swagger/OpenAPI annotations and examples to the Notion API specification.
- If Notion and local docs conflict, state the conflict explicitly and ask for a decision before implementing the conflicting behavior.
- Do not invent API shapes from local assumptions when a Notion endpoint spec exists.

## Secrets and Environment Files

- Do not open, read, print, summarize, or quote real secret files such as `.env`, `.env.*`, `application-secret.*`, or credential JSON files.
- Use `.env.example`, application config files without secrets, or user-provided key names when environment variable names are needed.
- Never commit API keys, tokens, passwords, client secrets, private keys, or full authorization headers.
- If a command accidentally prints a secret, do not repeat the value in chat or documentation. Stop using that output and ask the user to rotate the exposed credential if needed.
