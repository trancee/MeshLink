# Firebase Test Lab Setup Guide

This guide walks through setting up Firebase Test Lab to run MeshLink's
Android instrumented tests and iOS XCTests on real devices via GitHub Actions.

---

## Prerequisites

- A Google Cloud project with billing enabled
- Firebase enabled in that project
- The `gcloud` CLI installed locally (for initial setup)
- Owner or Editor access to the Google Cloud project

---

## Step 1: Enable Required APIs

```bash
export GCP_PROJECT_ID="your-project-id"

gcloud services enable testing.googleapis.com       --project="$GCP_PROJECT_ID"
gcloud services enable toolresults.googleapis.com   --project="$GCP_PROJECT_ID"
gcloud services enable iamcredentials.googleapis.com --project="$GCP_PROJECT_ID"
```

---

## Step 2: Create a Service Account

Create a dedicated service account for CI:

```bash
gcloud iam service-accounts create meshlink-test-lab \
  --display-name="MeshLink Firebase Test Lab" \
  --project="$GCP_PROJECT_ID"
```

Grant it the required roles:

```bash
SA_EMAIL="meshlink-test-lab@${GCP_PROJECT_ID}.iam.gserviceaccount.com"

# Permission to run tests on Firebase Test Lab
gcloud projects add-iam-policy-binding "$GCP_PROJECT_ID" \
  --member="serviceAccount:$SA_EMAIL" \
  --role="roles/firebase.testLabAdmin"

# Permission to write test results to Cloud Storage
gcloud projects add-iam-policy-binding "$GCP_PROJECT_ID" \
  --member="serviceAccount:$SA_EMAIL" \
  --role="roles/storage.objectAdmin"

# Permission to read test result details
gcloud projects add-iam-policy-binding "$GCP_PROJECT_ID" \
  --member="serviceAccount:$SA_EMAIL" \
  --role="roles/toolresults.editor"
```

---

## Step 3: Create a Cloud Storage Bucket for Results

```bash
export GCP_TEST_RESULTS_BUCKET="meshlink-test-results"

gcloud storage buckets create "gs://${GCP_TEST_RESULTS_BUCKET}" \
  --project="$GCP_PROJECT_ID" \
  --location="us-central1" \
  --uniform-bucket-level-access
```

---

## Step 4: Set Up Workload Identity Federation

Workload Identity Federation (WIF) lets GitHub Actions authenticate to Google
Cloud without storing long-lived service account keys. This is the recommended
approach.

### 4a. Create a Workload Identity Pool

```bash
gcloud iam workload-identity-pools create "github-actions" \
  --project="$GCP_PROJECT_ID" \
  --location="global" \
  --display-name="GitHub Actions"
```

### 4b. Create a Workload Identity Provider

Replace `trancee/MeshLink` with your GitHub `owner/repo`:

```bash
gcloud iam workload-identity-pools providers create-oidc "meshlink-repo" \
  --project="$GCP_PROJECT_ID" \
  --location="global" \
  --workload-identity-pool="github-actions" \
  --display-name="MeshLink GitHub Repo" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
  --attribute-condition="assertion.repository == 'trancee/MeshLink'" \
  --issuer-uri="https://token.actions.githubusercontent.com"
```

### 4c. Allow the GitHub repo to impersonate the service account

```bash
SA_EMAIL="meshlink-test-lab@${GCP_PROJECT_ID}.iam.gserviceaccount.com"
PROJECT_NUMBER=$(gcloud projects describe "$GCP_PROJECT_ID" --format="value(projectNumber)")

gcloud iam service-accounts add-iam-policy-binding "$SA_EMAIL" \
  --project="$GCP_PROJECT_ID" \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github-actions/attribute.repository/trancee/MeshLink"
```

### 4d. Get the Workload Identity Provider resource name

```bash
gcloud iam workload-identity-pools providers describe "meshlink-repo" \
  --project="$GCP_PROJECT_ID" \
  --location="global" \
  --workload-identity-pool="github-actions" \
  --format="value(name)"
```

This outputs a string like:
```
projects/123456789/locations/global/workloadIdentityPools/github-actions/providers/meshlink-repo
```

Save this — it's the `GCP_WORKLOAD_IDENTITY_PROVIDER` secret value.

---

## Step 5: Configure GitHub Repository Secrets

Go to **Settings → Secrets and variables → Actions** in your GitHub repository
and add these 4 secrets:

| Secret Name | Value | Example |
|-------------|-------|---------|
| `GCP_PROJECT_ID` | Your Google Cloud project ID | `meshlink-prod` |
| `GCP_SERVICE_ACCOUNT` | Service account email | `meshlink-test-lab@meshlink-prod.iam.gserviceaccount.com` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Full resource name from Step 4d | `projects/123456789/locations/global/workloadIdentityPools/github-actions/providers/meshlink-repo` |
| `GCP_TEST_RESULTS_BUCKET` | Cloud Storage bucket name | `meshlink-test-results` |

---

## Step 6: Verify the Setup

### Manual trigger

1. Go to **Actions → Firebase Test Lab** in your GitHub repository
2. Click **Run workflow** → select `main` branch → click **Run workflow**
3. Watch the workflow execution for both Android and iOS jobs

### Local test (optional)

You can test the gcloud commands locally:

```bash
# Build the Android test APK
./gradlew :meshlink:assembleDebugAndroidTest

# Find the APK
TEST_APK=meshlink/build/outputs/apk/androidTest/debug/meshlink-debug-androidTest.apk

# Run on Firebase Test Lab
gcloud firebase test android run \
  --type instrumentation \
  --app  "$TEST_APK" \
  --test "$TEST_APK" \
  --device model=Pixel6,version=34 \
  --timeout 10m \
  --project "$GCP_PROJECT_ID"
```

---

## How It Works

### Android Tests

The workflow builds a self-instrumenting test APK from the `androidTest`
source set. Since `meshlink` is a library module (AAR), there is no
separate app APK — the test APK is passed as both `--app` and `--test`
to Firebase Test Lab.

**Devices tested:**
- Pixel 2 (API 33 / Android 13)
- Pixel 6 (API 34 / Android 14)

### iOS Tests

The workflow builds a Kotlin/Native XCTest bundle targeting `iosArm64`
(physical devices), packages it as a `.zip`, and submits it to Firebase
Test Lab's iOS infrastructure.

**Devices tested:**
- iPhone 13 Pro (iOS 16.6)
- iPhone 14 Pro (iOS 17.5)

---

## Customizing Device Matrix

To change which devices tests run on, edit `.github/workflows/firebase-test-lab.yml`.

List available devices:

```bash
# Android devices
gcloud firebase test android models list --project="$GCP_PROJECT_ID"

# iOS devices
gcloud firebase test ios models list --project="$GCP_PROJECT_ID"
```

---

## Troubleshooting

### "Permission denied" or "403" errors

Verify the service account has all 3 roles:
```bash
gcloud projects get-iam-policy "$GCP_PROJECT_ID" \
  --flatten="bindings[].members" \
  --filter="bindings.members:meshlink-test-lab@" \
  --format="table(bindings.role)"
```

Expected output:
```
ROLE
roles/firebase.testLabAdmin
roles/storage.objectAdmin
roles/toolresults.editor
```

### "Workload Identity Federation" auth failures

- Ensure the `attribute-condition` matches your exact `owner/repo` string
- Verify the workflow has `permissions: id-token: write`
- Check that the provider resource name is the **full** path (starts with
  `projects/`)

### "No devices available" for iOS

Firebase Test Lab iOS device availability varies by region and plan. Check
current availability:
```bash
gcloud firebase test ios models list --project="$GCP_PROJECT_ID"
```

### Test timeout

The default timeout is 10 minutes. For longer test suites, increase
`--timeout` in the workflow file (max 45 minutes for physical devices).
