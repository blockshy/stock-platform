import { navigationGroups } from '../app/navigation.js';
import styles from '../app/Navigation.module.css';
import { useEffect, useRef, useState } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { stockApi } from '../api/stock.js';
import { useCurrentStock } from '../context/CurrentStockContext.jsx';

/** 内联线条图标，避免引入额外图标依赖 */
function Icon({ d }) {
    return (
        <svg className="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d={d}/>
        </svg>
    );
}


/** 顶栏右侧的股票搜索（联想逻辑沿用原顶部导航） */
export function StockSearch() {
    const navigate = useNavigate();
    const { setCurrentStock } = useCurrentStock();
    const [keyword, setKeyword] = useState('');
    const [options, setOptions] = useState([]);
    const [open, setOpen] = useState(false);
    const boxRef = useRef(null);

    useEffect(() => {
        const text = keyword.trim();
        if (!text) {
            setOptions([]);
            return;
        }
        const timer = setTimeout(async () => {
            try {
                setOptions(await stockApi.suggest(text, 8));
                setOpen(true);
            } catch {
                setOptions([]);
            }
        }, 220);
        return () => clearTimeout(timer);
    }, [keyword]);

    useEffect(() => {
        const onDocClick = (e) => {
            if (boxRef.current && !boxRef.current.contains(e.target)) setOpen(false);
        };
        document.addEventListener('mousedown', onDocClick);
        return () => document.removeEventListener('mousedown', onDocClick);
    }, []);

    const gotoCode = (code, name) => {
        setOpen(false);
        setKeyword('');
        // 顶栏搜索既是「跳详情」也是「设定全站当前股票」，一次操作两处生效
        setCurrentStock({ code, name: name || '' });
        navigate(`/stocks/${code}`, { state: { from: window.location.pathname + window.location.search } });
    };

    const goto = (item) => gotoCode(String(item.code), String(item.name ?? ''));

    return (
        <div className="search-box" ref={boxRef}>
            <input
                value={keyword}
                placeholder="搜索代码 / 名称，如 600519 贵州茅台"
                onChange={(e) => setKeyword(e.target.value)}
                onFocus={() => options.length && setOpen(true)}
                onKeyDown={(e) => {
                    if (e.key !== 'Enter') return;
                    if (options.length) goto(options[0]);
                    else if (/^\d{6}$/.test(keyword.trim())) gotoCode(keyword.trim(), '');
                }}
            />
            {open && options.length > 0 ? (
                <ul className="suggest-list">
                    {options.map((item) => (
                        <li key={item.code} onClick={() => goto(item)}>
                            <span className="code">{item.code}</span>
                            <span className="name">{item.name}</span>
                            {item.board ? <span className="tag">{item.board}</span> : null}
                        </li>
                    ))}
                </ul>
            ) : null}
        </div>
    );
}

/** 左侧固定侧栏：品牌 + 分组导航 + 底部数据源说明 */
export default function Sidebar({ onNavigate }) {
    return <aside className={`sidebar ${styles.sidebar}`}>
        <div className={styles.brand}>
            <span className={styles.logo}>S</span>
            <div><div className={styles.brandTitle}>股票研究平台</div>
                <div className={styles.brandCaption}>STOCK PLATFORM</div></div>
        </div>
        {navigationGroups.map((group) => <section className={styles.group} key={group.label}>
            <h2 className={styles.groupTitle}>{group.label}</h2>
            <nav className={styles.links} aria-label={group.label}>
                {group.items.map((item) => <NavLink key={item.to} to={item.to} end={item.end}
                    onClick={onNavigate} className={styles.link}>
                    <Icon d={item.icon}/><span>{item.label}</span>
                    {item.badge ? <span className={styles.badge}>{item.badge}</span> : null}
                </NavLink>)}
            </nav>
        </section>)}
        {/* 组件示例仅用于开发验收，生产构建不增加业务导航或后台请求。 */}
        {import.meta.env.DEV ? <section className={styles.group}>
            <h2 className={styles.groupTitle}>开发工具</h2>
            <NavLink to="/dev/ui" className={styles.link} onClick={onNavigate}>组件示例</NavLink>
        </section> : null}
        <div className={styles.foot}>
            <strong>本地数据 · 研究工作台</strong>
            <div>行情、预测与策略统一管理</div>
            <div>历史表现不代表未来结果</div>
        </div>
    </aside>;
}
