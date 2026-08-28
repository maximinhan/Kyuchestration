package cli

import (
	"fmt"
	"slices"
	"time"

	"github.com/maximinhan/Kyuchestration/internal/github"
	"github.com/maximinhan/Kyuchestration/internal/secretstore"
)

// fakeTokenStore 는 메모리에만 담아두는 TokenStore 다.
//
// 실제 저장소(키체인·파일)를 쓰지 않는 이유는 두 가지다. 표시 계층이 검증해야 하는 것은
// "무엇을 묻고 무엇을 저장하라고 시키는가" 이지 저장 자체가 아니고, 실제 저장소를 쓰면
// 테스트가 실행 머신의 키체인에 항목을 남긴다.
type fakeTokenStore struct {
	tokensByProfileName map[string]string
	profileOrder        []string
	storageKind         secretstore.StorageKind
}

var _ secretstore.TokenStore = (*fakeTokenStore)(nil)

func newFakeTokenStore(storageKind secretstore.StorageKind) *fakeTokenStore {
	return &fakeTokenStore{tokensByProfileName: map[string]string{}, storageKind: storageKind}
}

func (store *fakeTokenStore) Profiles() ([]secretstore.Profile, error) {
	profiles := make([]secretstore.Profile, 0, len(store.profileOrder))
	for _, profileName := range store.profileOrder {
		profiles = append(profiles, secretstore.Profile{Name: profileName, Storage: store.storageKind})
	}
	return profiles, nil
}

func (store *fakeTokenStore) SaveToken(profileName, token string) error {
	if _, isStored := store.tokensByProfileName[profileName]; !isStored {
		store.profileOrder = append(store.profileOrder, profileName)
	}
	store.tokensByProfileName[profileName] = token
	return nil
}

func (store *fakeTokenStore) LoadToken(profileName string) (string, error) {
	token, isStored := store.tokensByProfileName[profileName]
	if !isStored {
		return "", fmt.Errorf("%w: %s", secretstore.ErrProfileNotFound, profileName)
	}
	return token, nil
}

func (store *fakeTokenStore) RemoveToken(profileName string) error {
	if _, isStored := store.tokensByProfileName[profileName]; !isStored {
		return fmt.Errorf("%w: %s", secretstore.ErrProfileNotFound, profileName)
	}
	delete(store.tokensByProfileName, profileName)
	store.profileOrder = slices.DeleteFunc(store.profileOrder, func(name string) bool { return name == profileName })
	return nil
}

func (store *fakeTokenStore) StorageKindForNewToken() secretstore.StorageKind {
	return store.storageKind
}

// clonedRepository 는 fakeGitHub 가 받아 적은 클론 요청 하나다.
type clonedRepository struct {
	name            string
	destinationPath string
}

// fakeGitHub 는 GitHub 대신 미리 정한 답을 돌려주는 대역이다.
//
// 토큰을 검사한다 — 이 흐름의 핵심이 "어느 토큰으로 붙었는가" 라서, 대역이 토큰을 무시하면
// 저장된 프로필을 고르는 것과 새로 등록하는 것이 테스트에서 구분되지 않는다.
type fakeGitHub struct {
	acceptedToken            string
	owner                    github.Owner
	organizations            []github.Owner
	organizationsError       error
	repositoriesByOwnerLogin map[string][]github.Repository

	// cloneFailureByRepositoryName 은 특정 레포의 클론을 실패시킬 때 채운다.
	cloneFailureByRepositoryName map[string]error

	// tokensAskedFor 는 대역이 받아본 토큰 전부다. 어느 토큰으로 붙었는지 확인하는 데 쓴다.
	tokensAskedFor []string

	// repositoriesAskedFor 는 레포 목록을 물은 소유자 전부다.
	//
	// 이름만으로는 부족해서 소유자를 통째로 받아 적는다. 실제 구현은 IsOrganization 으로 API
	// 경로를 가르는데(개인은 /user/repos, 조직은 /orgs/{org}/repos), 이름만 보면 그 갈림이
	// 틀려도 대역은 같은 답을 돌려준다.
	repositoriesAskedFor []github.Owner

	clonedRepositories []clonedRepository
}

func (fake *fakeGitHub) newAccess(token string) github.RepositoryAccess {
	fake.tokensAskedFor = append(fake.tokensAskedFor, token)
	return &fakeRepositoryAccess{gitHub: fake, token: token}
}

// fakeRepositoryAccess 는 토큰 하나로 fakeGitHub 에 붙은 접근 경로다.
type fakeRepositoryAccess struct {
	gitHub *fakeGitHub
	token  string
}

var _ github.RepositoryAccess = (*fakeRepositoryAccess)(nil)

func (access *fakeRepositoryAccess) AuthenticatedOwner() (github.Owner, error) {
	if access.token != access.gitHub.acceptedToken {
		return github.Owner{}, fmt.Errorf("%w (Bad credentials)", github.ErrInvalidToken)
	}
	return access.gitHub.owner, nil
}

func (access *fakeRepositoryAccess) Organizations() ([]github.Owner, error) {
	if access.token != access.gitHub.acceptedToken {
		return nil, fmt.Errorf("%w (Bad credentials)", github.ErrInvalidToken)
	}
	if access.gitHub.organizationsError != nil {
		return nil, access.gitHub.organizationsError
	}
	return access.gitHub.organizations, nil
}

func (access *fakeRepositoryAccess) Repositories(owner github.Owner) ([]github.Repository, error) {
	access.gitHub.repositoriesAskedFor = append(access.gitHub.repositoriesAskedFor, owner)
	return access.gitHub.repositoriesByOwnerLogin[owner.Login], nil
}

func (access *fakeRepositoryAccess) CloneRepository(repository github.Repository, destinationPath string) error {
	if err := access.gitHub.cloneFailureByRepositoryName[repository.Name]; err != nil {
		return err
	}
	access.gitHub.clonedRepositories = append(access.gitHub.clonedRepositories,
		clonedRepository{name: repository.Name, destinationPath: destinationPath})
	return nil
}

// makeRepository 는 목록에 나올 레포 하나를 만든다.
func makeRepository(name string, isPrivate bool, updatedAt time.Time) github.Repository {
	return github.Repository{
		Name:          name,
		OwnerLogin:    "maximinhan",
		CloneURL:      "https://github.com/maximinhan/" + name + ".git",
		IsPrivate:     isPrivate,
		LastUpdatedAt: updatedAt,
	}
}
