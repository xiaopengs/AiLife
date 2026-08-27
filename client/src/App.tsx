import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import DashboardLayout from "@/components/DashboardLayout";
import Analytics from "@/pages/Analytics";
import Calendar from "@/pages/Calendar";
import Home from "@/pages/Home";
import Library from "@/pages/Library";
import NotFound from "@/pages/NotFound";
import Workflow from "@/pages/Workflow";
import { Route, Switch } from "wouter";
import ErrorBoundary from "./components/ErrorBoundary";
import { ThemeProvider } from "./contexts/ThemeContext";

const workspace = (Page: () => React.ReactElement) => () => <DashboardLayout><Page /></DashboardLayout>;

function Router() {
  return <Switch>
    <Route path="/" component={workspace(Home)} />
    <Route path="/library" component={workspace(Library)} />
    <Route path="/workflow" component={workspace(Workflow)} />
    <Route path="/calendar" component={workspace(Calendar)} />
    <Route path="/analytics" component={workspace(Analytics)} />
    <Route path="/404" component={NotFound} />
    <Route component={NotFound} />
  </Switch>;
}

export default function App() {
  return <ErrorBoundary><ThemeProvider defaultTheme="light"><TooltipProvider><Toaster /><Router /></TooltipProvider></ThemeProvider></ErrorBoundary>;
}
