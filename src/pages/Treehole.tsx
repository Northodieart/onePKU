import { useEffect, useState, useRef } from "react";
import { MessageCircle, Heart, Search as SearchIcon, X } from "lucide-react";
import { fmtTime, useResource, openOfficial, type Hole } from "../lib/api";
import {
  Button,
  Empty,
  Resource,
  Pager,
  Modal,
  type Login,
} from "../components/ui";
export default function Treehole({ login }: { login: Login }) {
  const searchInput = useRef<HTMLInputElement>(null);
  const [input, setInput] = useState("");
  const [composing, setComposing] = useState(false);
  const [filter, setFilter] = useState({ search: "", page: 1 });
  const [selected, setSelected] = useState<number>();
  useEffect(() => {
    if (composing) return;
    const t = setTimeout(
      () => setFilter({ search: input.trim(), page: 1 }),
      300,
    );
    return () => clearTimeout(t);
  }, [input, composing]);
  const q = useResource<Hole[]>({ kind: "holes", ...filter });
  return (
    <>
      <header className="page-heading">
        <div>
          <h1>树洞</h1>
        </div>
        <Button onClick={() => void openOfficial("treehole")}>打开树洞</Button>
      </header>
      <div className="toolbar">
        <div className="search">
          <SearchIcon size={17} />
          <input
            ref={searchInput}
            aria-label="搜索树洞"
            type="search"
            placeholder="搜索内容，或在这里随便看看"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.nativeEvent.isComposing && !composing)
                setFilter({ search: input.trim(), page: 1 });
            }}
            onCompositionStart={() => setComposing(true)}
            onCompositionEnd={() => setComposing(false)}
          />
          {input && (
            <button
              className="icon-button"
              aria-label="清空搜索"
              onClick={() => {
                setInput("");
                setFilter({ search: "", page: 1 });
                searchInput.current?.focus();
              }}
            >
              <X size={15} />
            </button>
          )}
        </div>
      </div>
      <Resource
        title={filter.search ? `“${filter.search}”的搜索结果` : "最新树洞"}
        q={q}
        login={login}
        service="treehole"
      >
        {(data) =>
          data.length ? (
            <>
              <div className="hole-list">
                {data.map((h) => (
                  <button
                    className="hole-card"
                    key={h.pid}
                    onClick={() => setSelected(h.pid)}
                  >
                    <div className="hole-meta">
                      <span>#{h.pid}</span>
                      <time>{fmtTime(h.timestamp)}</time>
                    </div>
                    <p>{h.text}</p>
                    {h.media_ids && (
                      <span className="subtle">含媒体 · 在原网站查看</span>
                    )}
                    <div className="hole-stats">
                      <span>
                        <MessageCircle size={14} />
                        {h.reply}
                      </span>
                      <span>
                        <Heart size={14} />
                        {h.likenum}
                      </span>
                    </div>
                  </button>
                ))}
              </div>
              <Pager
                page={filter.page}
                onChange={(p) => setFilter({ ...filter, page: p })}
                hasNext={data.length === 20}
                busy={q.isFetching}
              />
            </>
          ) : (
            <>
              <Empty icon={<MessageCircle />}>
                {filter.search ? "没有找到匹配的树洞" : "这一页暂无树洞"}
              </Empty>
              {filter.page > 1 && (
                <Pager
                  page={filter.page}
                  onChange={(p) => setFilter({ ...filter, page: p })}
                  hasNext={false}
                />
              )}
            </>
          )
        }
      </Resource>
      {selected && (
        <HoleDetail
          id={selected}
          login={login}
          onClose={() => setSelected(undefined)}
        />
      )}
    </>
  );
}
function HoleDetail({
  id,
  login,
  onClose,
}: {
  id: number;
  login: Login;
  onClose: () => void;
}) {
  const q = useResource<{
    hole: Hole;
    list: {
      cid: number;
      text: string;
      name_tag: string;
      is_lz: number;
      timestamp: number;
    }[];
    total: number | null;
  }>({ kind: "hole", id });
  return (
    <Modal title={`树洞 #${id}`} open onClose={onClose} wide>
      <Resource title="树洞与回复" q={q} login={login} service="treehole">
        {(data) => (
          <>
            <p className="prose hole-original">{data.hole.text}</p>
            <div className="detail-meta">
              {fmtTime(data.hole.timestamp)} · {data.hole.reply} 条回复
            </div>
            <div className="comments">
              {data.list.map((c) => (
                <article key={c.cid}>
                  <div>
                    <strong>{c.is_lz ? "洞主" : c.name_tag || "匿名"}</strong>
                    <time>{fmtTime(c.timestamp)}</time>
                  </div>
                  <p className="prose">{c.text}</p>
                </article>
              ))}
            </div>
            {!data.list.length && <Empty>暂无回复</Empty>}
            {data.hole.reply > data.list.length && (
              <p className="footnote">
                当前接口返回 {data.list.length} 条回复，更多回复请打开树洞查看。
              </p>
            )}
          </>
        )}
      </Resource>
    </Modal>
  );
}
