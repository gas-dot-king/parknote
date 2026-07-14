# GitHub 업로드 준비

현재 저장소는 Git으로 초기화돼 있고, 빌드 산출물·서명 키·IDE 설정은 `.gitignore`로 제외된다.
GitHub에 올리려면 먼저 GitHub CLI를 설치하고 로그인해야 한다.

```powershell
gh auth login
gh auth status
```

## 저장소를 새로 만들 때

공개/비공개 여부와 저장소 이름을 먼저 정한다. 개인정보 처리방침을 GitHub Pages로 호스팅하려면
공개 URL이 필요하며, 소스 공개를 원하지 않으면 별도 웹 호스팅을 사용한다.

```powershell
# 예: 현재 폴더를 새 비공개 저장소로 만들고 master를 업로드
gh repo create amatda-parking --private --source=. --remote=origin --push
```

공개 저장소를 원하면 `--private` 대신 `--public`을 사용한다. 기존 GitHub 저장소에 연결할 경우에는
다음처럼 원격 URL을 지정한다.

```powershell
git remote add origin https://github.com/[OWNER]/[REPOSITORY].git
git push -u origin master
```

업로드 뒤에는 GitHub Actions의 **Android CI**가 `assembleDebug`를 실행한다. `keystore.properties`,
업로드 키, AAB/APK는 절대 원격 저장소에 올리지 않는다.
