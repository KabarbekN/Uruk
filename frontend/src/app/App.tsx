import {
  Component,
  lazy,
  Suspense,
  useEffect,
  useState,
  type ReactNode,
} from 'react';
import {
  Link,
  Navigate,
  NavLink,
  Route,
  Routes,
  useLocation,
  useMatch,
} from 'react-router-dom';
import {
  Activity,
  ChevronRight,
  FileCheck2,
  FolderGit2,
  GitCompareArrows,
  LayoutDashboard,
  Menu,
  Network,
  Settings2,
  Terminal,
  X,
} from 'lucide-react';
import { useProjects, useRun, useRuns } from '../shared/api/queries';
import { useLocale, useT } from '../shared/lib/i18n';
import { Button, EmptyState, IconButton, Loading } from '../shared/ui';
import { Notifications } from '../shared/ui/notifications';

const ProjectsPage = lazy(() => import('../pages/ProjectsPage'));
const ProjectPage = lazy(() => import('../pages/ProjectPage'));
const CanvasPage = lazy(() => import('../pages/CanvasPage'));
const ReviewPage = lazy(() => import('../pages/ReviewPage'));
const DiffPage = lazy(() => import('../pages/DiffPage'));
const DebugPage = lazy(() => import('../pages/DebugPage'));
const SettingsPage = lazy(() => import('../pages/SettingsPage'));

function ErrorFallback() {
  const { t } = useT();
  return (
    <EmptyState icon={Terminal} title={t('crash')}>
      <Button onClick={() => window.location.reload()}>{t('reload')}</Button>
    </EmptyState>
  );
}
class ViewBoundary extends Component<
  { children: ReactNode },
  { failed: boolean }
> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    return this.state.failed ? <ErrorFallback /> : this.props.children;
  }
}
function MissingPage() {
  const { t } = useT();
  return (
    <EmptyState icon={FolderGit2} title={t('notFound')}>
      <Link to="/projects">{t('goProjects')}</Link>
    </EmptyState>
  );
}

export default function App() {
  const { t, locale } = useT();
  const location = useLocation();
  const projectMatch = useMatch('/projects/:projectId/*');
  const runMatch = useMatch('/analyses/:analysisRunId/*');
  const run = useRun(runMatch?.params.analysisRunId);
  const projectId = projectMatch?.params.projectId || run.data?.projectId;
  const runs = useRuns(projectId);
  const projects = useProjects();
  const project = projects.data?.find((item) => item.id === projectId);
  const latestRun = [...(runs.data ?? [])].sort((a, b) =>
    b.createdAt.localeCompare(a.createdAt),
  )[0];
  const runId = runMatch?.params.analysisRunId || latestRun?.id;
  const [menuOpen, setMenuOpen] = useState(false);
  useEffect(() => setMenuOpen(false), [location.pathname]);
  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">
        {t('skip')}
      </a>
      {menuOpen && (
        <button
          className="sidebar-scrim"
          aria-label={t('close')}
          onClick={() => setMenuOpen(false)}
        />
      )}
      <aside className={`sidebar ${menuOpen ? 'open' : ''}`}>
        <Link to="/projects" className="brand">
          <span className="brand-mark">
            <Network size={23} />
          </span>
          <span>
            Semantic
            <br />
            <strong>Business Map</strong>
          </span>
        </Link>
        <div className="workspace-label">
          <span className="workspace-initial">S</span>
          <span>{t('workspace')}</span>
        </div>
        <nav aria-label={t('navigation')}>
          <NavLink to="/projects" end>
            <FolderGit2 size={17} />
            {t('projects')}
          </NavLink>
          {projectId && (
            <>
              <div className="nav-section-label" title={project?.name}>
                {project?.name || t('project')}
              </div>
              <NavLink to={`/projects/${projectId}`} end>
                <LayoutDashboard size={17} />
                {t('overview')}
              </NavLink>
              <NavLink to={`/projects/${projectId}/analyses`}>
                <Activity size={17} />
                {t('analyses')}
              </NavLink>
              <NavLink to={`/projects/${projectId}/review`}>
                <FileCheck2 size={17} />
                {t('review')}
              </NavLink>
            </>
          )}
          {runId && (
            <>
              <div className="nav-section-label">{t('analyses')}</div>
              <NavLink to={`/analyses/${runId}/canvas`}>
                <Network size={17} />
                {t('canvas')}
              </NavLink>
              <NavLink to={`/analyses/${runId}/diff`}>
                <GitCompareArrows size={17} />
                {t('diff')}
              </NavLink>
              <NavLink to={`/analyses/${runId}/debug`}>
                <Terminal size={17} />
                {t('debug')}
              </NavLink>
            </>
          )}
        </nav>
        <div className="sidebar-bottom">
          <NavLink to="/settings">
            <Settings2 size={17} />
            {t('settings')}
          </NavLink>
          <div className="sidebar-version">
            <span className="small-mark" />
            {t('app')}
            <span>0.1</span>
          </div>
        </div>
      </aside>
      <div className="workspace-main">
        <header className="workspace-header">
          <div className="breadcrumbs">
            <span className="mobile-nav">
              <IconButton
                icon={menuOpen ? X : Menu}
                label={t(menuOpen ? 'close' : 'openMenu')}
                onClick={() => setMenuOpen(!menuOpen)}
              />
            </span>
            <Link to="/projects">{t('workspace')}</Link>
            <ChevronRight size={13} />
            <span>
              {project?.name ||
                t(location.pathname === '/settings' ? 'settings' : 'projects')}
            </span>
          </div>
          <select
            className="locale-select"
            aria-label={t('language')}
            value={locale}
            onChange={(event) =>
              useLocale
                .getState()
                .setLocale(event.target.value === 'ru' ? 'ru' : 'en')
            }
          >
            <option value="en">EN</option>
            <option value="ru">RU</option>
          </select>
        </header>
        <main id="main-content" className="main-content">
          <ViewBoundary key={location.pathname}>
            <Suspense fallback={<Loading />}>
              <Routes>
                <Route path="/" element={<Navigate to="/projects" replace />} />
                <Route path="/projects" element={<ProjectsPage />} />
                <Route path="/projects/:projectId" element={<ProjectPage />} />
                <Route
                  path="/projects/:projectId/analyses"
                  element={<ProjectPage />}
                />
                <Route
                  path="/projects/:projectId/review"
                  element={<ReviewPage />}
                />
                <Route
                  path="/analyses/:analysisRunId/canvas"
                  element={<CanvasPage />}
                />
                <Route
                  path="/analyses/:analysisRunId/diff"
                  element={<DiffPage />}
                />
                <Route
                  path="/analyses/:analysisRunId/debug"
                  element={<DebugPage />}
                />
                <Route path="/settings" element={<SettingsPage />} />
                <Route path="*" element={<MissingPage />} />
              </Routes>
            </Suspense>
          </ViewBoundary>
        </main>
      </div>
      <Notifications />
    </div>
  );
}
