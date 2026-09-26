package wsserver

import "encoding/json"

// unmarshal แยกไว้เพื่อให้เทสต์ได้ง่าย
func unmarshal(b []byte, v any) error { return json.Unmarshal(b, v) }
