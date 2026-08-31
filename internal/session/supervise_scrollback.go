package session

import "sync"

// scrollbackRingCapacity 는 세션 하나가 쥐는 출력 링의 크기다.
//
// **잠정값이다**(설계 문서 10절 1번). tmux 는 창당 2000 줄로 재지만 이쪽은 원시 바이트 링이라
// 줄이 아니라 바이트로 재야 하고, 색과 커서 이동이 섞인 출력은 한 줄이 수백 바이트가 되기도 한다.
// 정하는 근거는 "화면 전체를 그리는 프로그램의 다시 그리기 한 번이 담기는가" 이고, 2 단계의
// 실측 결과가 PR 본문에 있다.
//
// 링이 감독마다 하나이므로 이 숫자가 곧 ps 에 보이는 감독 하나의 크기다(설계 문서 5.7).
const scrollbackRingCapacity = 4 << 20

// sessionScrollback 은 세션 출력 원시 바이트의 고정 크기 링이다.
//
// **붙어 있는 클라이언트에게 가는 바이트도 전부 이 링을 지난다.** 재생과 실시간이 서로 다른
// 경로였다면 그 둘이 만나는 자리에서 바이트가 새거나 겹칠 수 있는데, 클라이언트가 링에 커서
// 하나를 두고 앞으로 읽어가는 모양이면 그 경계가 아예 없다 — 재생은 커서가 뒤에서 시작하는
// 것일 뿐이고, 따라잡은 뒤로는 같은 읽기가 실시간이 된다.
//
// 화면 모델이 아니라 바이트 링이다. 감독은 출력을 해석해 무언가를 판단하지 않는다(설계 원칙 9).
type sessionScrollback struct {
	mutex sync.Mutex

	// changed 는 새 바이트가 들어오거나 출력이 끝나면 닫히고 새것으로 갈린다.
	// 기다리는 쪽이 여럿이어도 닫힘 하나로 전부 깨어난다.
	changed chan struct{}

	buffer []byte

	// written 은 세션이 지금까지 뱉은 총 바이트 수다. 링에 남은 양이 아니라 절대 위치다.
	// 커서를 절대 오프셋으로 두면 링이 몇 바퀴 감겼는지를 읽는 쪽이 셈하지 않아도 된다.
	written int64

	// oldest 는 링이 아직 갖고 있는 가장 오래된 바이트의 절대 오프셋이다.
	oldest int64

	// ended 는 세션 출력이 끝났다는 표시다 — PTY 마스터가 EOF 를 준 뒤다.
	ended bool
}

func newSessionScrollback(capacity int) *sessionScrollback {
	return &sessionScrollback{
		changed: make(chan struct{}),
		buffer:  make([]byte, capacity),
	}
}

// append 는 세션이 뱉은 바이트를 링에 적고 기다리던 읽기를 깨운다.
func (s *sessionScrollback) append(chunk []byte) {
	if len(chunk) == 0 {
		return
	}

	s.mutex.Lock()
	defer s.mutex.Unlock()

	capacity := int64(len(s.buffer))
	if int64(len(chunk)) > capacity {
		// 링보다 큰 덩어리는 뒤쪽만 남는다. 앞부분은 적어봐야 같은 덩어리의 뒷부분에 덮인다.
		// 적지 않고 지나가되 총량에는 세어, 절대 오프셋과 링 안의 자리가 계속 맞물리게 한다.
		skipped := int64(len(chunk)) - capacity
		s.written += skipped
		chunk = chunk[skipped:]
	}

	for len(chunk) > 0 {
		position := int(s.written % capacity)
		copied := copy(s.buffer[position:], chunk)
		s.written += int64(copied)
		chunk = chunk[copied:]
	}

	if s.written > capacity {
		s.oldest = s.written - capacity
	}
	s.wakeWaiters()
}

// finish 는 더 올 바이트가 없음을 알린다.
//
// 기다리던 읽기를 깨워야 붙어 있는 클라이언트가 EXIT 를 받고 나갈 수 있다.
func (s *sessionScrollback) finish() {
	s.mutex.Lock()
	defer s.mutex.Unlock()

	if s.ended {
		return
	}
	s.ended = true
	s.wakeWaiters()
}

// read 는 커서 자리의 바이트를 into 에 담아 돌려준다. 읽을 것이 없으면 생길 때까지 기다린다.
//
// 세 번째 반환값이 false 면 더 읽을 것이 없다 — 세션 출력이 끝났거나 이 클라이언트가 끊긴 것이다.
// 링이 감기는 자리에서 끊어 돌려주므로 부르는 쪽은 커서를 물려가며 이어 붙인다.
func (s *sessionScrollback) read(cursor int64, into []byte, clientGone <-chan struct{}) (int, int64, bool) {
	for {
		s.mutex.Lock()

		if cursor < s.oldest {
			// 링이 감기는 것보다 뒤처진 클라이언트다. 없는 바이트를 기다리면 그 연결은 영영
			// 끝나지 않으므로, 남아 있는 가장 오래된 자리로 당겨 이어 보낸다.
			cursor = s.oldest
		}

		if cursor < s.written {
			capacity := int64(len(s.buffer))
			position := cursor % capacity
			readable := min(int64(len(into)), s.written-cursor, capacity-position)
			copy(into[:readable], s.buffer[position:position+readable])
			s.mutex.Unlock()
			return int(readable), cursor + readable, true
		}

		if s.ended {
			s.mutex.Unlock()
			return 0, cursor, false
		}

		newBytesArrived := s.changed
		s.mutex.Unlock()

		select {
		case <-newBytesArrived:
		case <-clientGone:
			return 0, cursor, false
		}
	}
}

// replayStartOffset 은 새로 붙은 클라이언트에게 흘려보낼 첫 바이트의 자리다.
//
// 링이 아직 아무것도 버리지 않았으면 세션의 첫 바이트부터다 — 잘린 조각이 있을 수 없다.
//
// 버린 것이 있으면 첫 개행 다음으로 민다. 링이 오래된 바이트를 버릴 때 하필 이스케이프 시퀀스나
// UTF-8 문자의 한가운데를 자를 수 있는데, 개행은 CSI 시퀀스 안에 들어가지 않고 ASCII 라 문자
// 경계이기도 하다. 그 한 줄을 버리는 값으로 재생이 온전한 자리에서 시작한다(설계 문서 5.7 대응 1).
//
// **이것은 5.7 이 지목한 위험의 해법이 아니다.** 링이 시작하기 전의 바이트가 정해둔 화면 상태
// (대체 화면 버퍼·커서 위치·걸려 있는 색)는 재생에 없고, 그것은 감독 안에 화면 모델을 두어야
// 풀린다. 여기서 막는 것은 첫 줄에 찍히는 깨진 조각까지다.
func (s *sessionScrollback) replayStartOffset() int64 {
	s.mutex.Lock()
	defer s.mutex.Unlock()

	if s.oldest == 0 {
		return 0
	}

	capacity := int64(len(s.buffer))
	for offset := s.oldest; offset < s.written; offset++ {
		if s.buffer[offset%capacity] == '\n' {
			return offset + 1
		}
	}

	// 화면 전체를 그리는 프로그램의 출력에는 개행이 없을 수 있다. 재생을 포기하는 것보다
	// 첫 조각을 감수하고 내보내는 편이 낫다 — 화면이 비는 것이 더 나쁘다.
	return s.oldest
}

// oldestOffset 은 링이 아직 갖고 있는 가장 오래된 바이트의 절대 오프셋이다.
func (s *sessionScrollback) oldestOffset() int64 {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	return s.oldest
}

// writtenBytes 는 세션이 지금까지 뱉은 총 바이트 수다.
func (s *sessionScrollback) writtenBytes() int64 {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	return s.written
}

// wakeWaiters 는 기다리는 읽기를 전부 깨운다. 잠금을 쥔 채로 부른다.
func (s *sessionScrollback) wakeWaiters() {
	close(s.changed)
	s.changed = make(chan struct{})
}
