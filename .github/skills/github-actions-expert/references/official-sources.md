# Official Sources

Authoritative references for GitHub Actions. When in doubt, these are the source of truth.

## Core documentation

- **Workflow syntax**: https://docs.github.com/en/actions/writing-workflows/workflow-syntax-for-github-actions
- **Events that trigger workflows**: https://docs.github.com/en/actions/using-workflows/events-that-trigger-workflows
- **Contexts reference**: https://docs.github.com/en/actions/learn-github-actions/contexts
- **Expressions**: https://docs.github.com/en/actions/learn-github-actions/expressions
- **Variables reference**: https://docs.github.com/en/actions/reference/variables-reference
- **Workflow commands**: https://docs.github.com/en/actions/using-workflows/workflow-commands-for-github-actions

## Security

- **Security hardening**: https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions
- **Automatic token authentication (GITHUB_TOKEN)**: https://docs.github.com/en/actions/security-guides/automatic-token-authentication
- **Using secrets**: https://docs.github.com/en/actions/security-guides/using-secrets-in-github-actions
- **OIDC for cloud deployments**: https://docs.github.com/en/actions/deployment/security-hardening-your-deployments/about-security-hardening-with-openid-connect

## Reuse and sharing

- **Reusable workflows**: https://docs.github.com/en/actions/using-workflows/reusing-workflows
- **Creating composite actions**: https://docs.github.com/en/actions/creating-actions/creating-a-composite-action
- **Creating JavaScript actions**: https://docs.github.com/en/actions/creating-actions/creating-a-javascript-action
- **Creating Docker actions**: https://docs.github.com/en/actions/creating-actions/creating-a-docker-container-action

## Runners and environments

- **GitHub-hosted runners**: https://docs.github.com/en/actions/using-github-hosted-runners/about-github-hosted-runners
- **Self-hosted runners**: https://docs.github.com/en/actions/hosting-your-own-runners/managing-self-hosted-runners/about-self-hosted-runners
- **Managing environments**: https://docs.github.com/en/actions/deployment/targeting-different-environments/managing-environments-for-deployment

## Performance and storage

- **Caching dependencies**: https://docs.github.com/en/actions/writing-workflows/choosing-what-your-workflow-does/caching-dependencies-to-speed-up-workflows
- **Storing artifacts**: https://docs.github.com/en/actions/using-workflows/storing-workflow-data-as-artifacts
- **Usage limits**: https://docs.github.com/en/actions/reference/usage-limits-billing-and-administration

## Key official actions

| Action | Purpose | Repo |
|--------|---------|------|
| `actions/checkout` | Check out repository code | https://github.com/actions/checkout |
| `actions/cache` | Cache dependencies and build outputs | https://github.com/actions/cache |
| `actions/upload-artifact` | Upload build artifacts | https://github.com/actions/upload-artifact |
| `actions/download-artifact` | Download artifacts from other jobs | https://github.com/actions/download-artifact |
| `actions/setup-node` | Set up Node.js with caching | https://github.com/actions/setup-node |
| `actions/setup-python` | Set up Python with caching | https://github.com/actions/setup-python |
| `actions/setup-go` | Set up Go with caching | https://github.com/actions/setup-go |
| `actions/setup-java` | Set up Java/Gradle/Maven with caching | https://github.com/actions/setup-java |
| `actions/github-script` | Run JavaScript with the GitHub API | https://github.com/actions/github-script |
| `actions/labeler` | Auto-label PRs by changed files | https://github.com/actions/labeler |
| `docker/build-push-action` | Build and push Docker images | https://github.com/docker/build-push-action |
| `docker/login-action` | Log in to container registries | https://github.com/docker/login-action |
| `docker/metadata-action` | Generate Docker image tags and labels | https://github.com/docker/metadata-action |
| `aws-actions/configure-aws-credentials` | Configure AWS credentials (OIDC) | https://github.com/aws-actions/configure-aws-credentials |
| `aws-actions/amazon-ecs-deploy-task-definition` | Deploy to ECS | https://github.com/aws-actions/amazon-ecs-deploy-task-definition |
| `azure/login` | Azure OIDC login | https://github.com/azure/login |
| `google-github-actions/auth` | GCP OIDC authentication | https://github.com/google-github-actions/auth |
| `dorny/paths-filter` | Detect changed files for monorepo CI | https://github.com/dorny/paths-filter |
| `softprops/action-gh-release` | Create GitHub releases | https://github.com/softprops/action-gh-release |

## Starter workflows

GitHub maintains a collection of template workflows for common languages and use cases:
https://github.com/actions/starter-workflows
